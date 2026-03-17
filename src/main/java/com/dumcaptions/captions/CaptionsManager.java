package com.dumcaptions.captions;

import com.dumcaptions.translate.GroqClient;
import com.dumcaptions.vad.TenVad;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.audio.AudioReceiveHandler;
import net.dv8tion.jda.api.audio.OpusPacket;
import net.dv8tion.jda.api.audio.UserAudio;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.selections.SelectOption;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.managers.AudioManager;
import io.github.jaredmdobson.concentus.OpusDecoder;
import io.github.jaredmdobson.concentus.OpusException;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.io.IOException;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public class CaptionsManager extends ListenerAdapter {
    private static final Logger logger = LoggerFactory.getLogger(CaptionsManager.class);

    private final JDA jda;
    private final GroqClient groq;
    private final Map<String, VoiceSession> sessions = new ConcurrentHashMap<>(); // GuildID -> Session
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final ExecutorService audioExecutor = Executors.newFixedThreadPool(4);
    private final AtomicLong nextReqTime = new AtomicLong(0);

    public CaptionsManager(JDA jda, GroqClient groq) {
        this.jda = jda;
        this.groq = groq;
        
        // Start the ticker for processing buffers
        scheduler.scheduleAtFixedRate(this::checkBuffers, 200, 200, TimeUnit.MILLISECONDS);
    }

    public static class VoiceSession {
        public final String guildId;
        public final String textChannelId;
        public final Map<Long, AudioBuffer> userAudio = new ConcurrentHashMap<>();
        public final Map<Long, String> lastUserText = new ConcurrentHashMap<>();
        public final List<String> userLogs = new ArrayList<>();
        public final Map<Long, Float> userVadThresholds = new ConcurrentHashMap<>();
        public final Map<Long, Integer> userVadDroppedSequential = new ConcurrentHashMap<>();
        public final Map<Long, Boolean> userHasPassedVad = new ConcurrentHashMap<>();
        public String embedMsgId;
        public String captionMode = "english";

        public VoiceSession(String guildId, String textChannelId) {
            this.guildId = guildId;
            this.textChannelId = textChannelId;
        }
    }

    @Override
    public void onStringSelectInteraction(StringSelectInteractionEvent event) {
        String componentId = event.getComponentId();
        if (componentId.startsWith("caption_mode_")) {
            String guildId = componentId.substring("caption_mode_".length());
            VoiceSession session = sessions.get(guildId);
            if (session == null) {
                event.reply("Session has ended.").setEphemeral(true).queue();
                return;
            }
            String selected = event.getValues().get(0);
            session.captionMode = selected;

            event.editComponents(ActionRow.of(createSelectionMenu(guildId, selected))).queue();
        }
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.getName().equals("captions")) return;

        String subcommand = event.getSubcommandName();
        if ("on".equals(subcommand)) {
            handleOn(event);
        } else if ("off".equals(subcommand)) {
            handleOff(event);
        }
    }

    private void handleOn(SlashCommandInteractionEvent event) {
        Guild guild = event.getGuild();
        Member member = event.getMember();
        if (guild == null || member == null) return;

        AudioChannel vc = member.getVoiceState().getChannel();
        if (vc == null) {
            event.reply("Please join a voice channel first.").setEphemeral(true).queue();
            return;
        }

        if (sessions.containsKey(guild.getId())) {
            event.reply("Captions are already running in this guild.").setEphemeral(true).queue();
            return;
        }

        event.deferReply().queue();

        AudioManager audioManager = guild.getAudioManager();
        audioManager.setReceivingHandler(new GuildAudioHandler(guild.getId()));
        audioManager.openAudioConnection(vc);

        VoiceSession session = new VoiceSession(guild.getId(), event.getChannel().getId());
        sessions.put(guild.getId(), session);

        // Send initial embed
        EmbedBuilder eb = new EmbedBuilder()
                .setTitle("whisper-large-v3 (English)")
                .setDescription("Listening for voices...")
                .setColor(Color.GREEN)
                .setFooter("Powered by Groq (Large-Whisper-v3)");

        event.getChannel().sendMessageEmbeds(eb.build())
                .setComponents(ActionRow.of(createSelectionMenu(guild.getId(), "english")))
                .setSuppressedNotifications(true)
                .queue(msg -> {
            session.embedMsgId = msg.getId();
            event.getHook().editOriginal("Captions enabled. I've joined " + vc.getName()).queue();
        });
    }

    private void handleOff(SlashCommandInteractionEvent event) {
        String guildId = event.getGuild().getId();
        VoiceSession session = sessions.remove(guildId);
        
        if (session == null) {
            event.reply("Captions are not running.").setEphemeral(true).queue();
            return;
        }

        AudioManager audioManager = event.getGuild().getAudioManager();
        audioManager.closeAudioConnection();
        
        // Delete the embed
        MessageChannel channel = jda.getChannelById(MessageChannel.class, session.textChannelId);
        if (channel != null && session.embedMsgId != null) {
            channel.deleteMessageById(session.embedMsgId).queue(
                null, 
                err -> logger.warn("Failed to delete captures message: {}", err.getMessage())
            );
        }

        event.reply("Captions disabled.").queue();
    }

    private class GuildAudioHandler implements AudioReceiveHandler {
        private final String guildId;

        public GuildAudioHandler(String guildId) {
            this.guildId = guildId;
        }

        @Override
        public boolean canReceiveEncoded() {
            return true;
        }

        @Override
        public void handleEncodedAudio(OpusPacket packet) {
            VoiceSession session = sessions.get(guildId);
            if (session == null) return;

            long userId = packet.getUserId();
            AudioBuffer buf = session.userAudio.computeIfAbsent(userId, AudioBuffer::new);
            buf.push(packet.getOpusAudio());
        }

        @Override
        public void handleUserAudio(UserAudio userAudio) {
            // We use handleEncodedAudio
        }
    }

    private void checkBuffers() {
        for (VoiceSession session : sessions.values()) {
            for (Map.Entry<Long, AudioBuffer> entry : session.userAudio.entrySet()) {
                long ssrc = entry.getKey();
                AudioBuffer buf = entry.getValue();
                
                AudioBuffer.ShouldProcessResult res = buf.shouldProcess();
                if (res.shouldProcess) {
                    if (res.isHardCutoff || res.isStale) {
                        processChunk(session, ssrc, buf.pop(res.isHardCutoff));
                    } else if (canRequest()) {
                        processChunk(session, ssrc, buf.pop(false));
                    }
                }
            }
        }
    }

    private boolean canRequest() {
        long now = System.currentTimeMillis();
        if (now < nextReqTime.get()) return false;
        nextReqTime.set(now + CaptionsConfig.RATE_LIMIT_INTERVAL_MS);
        return true;
    }

    private void processChunk(VoiceSession session, long userId, List<byte[]> packets) {
        if (packets.size() < 25) return; // Ignore small clicks

        audioExecutor.submit(() -> {
            String displayName = "Unknown (" + userId + ")";
            try {
                Member member = jda.getGuildById(session.guildId).getMemberById(userId);
                if (member != null) {
                    displayName = member.getEffectiveName();
                } else {
                    User user = jda.getUserById(userId);
                    if (user != null) {
                        displayName = user.getEffectiveName();
                    }
                }

                // VAD Filtering
                float vadThreshold = session.userVadThresholds.getOrDefault(userId, CaptionsConfig.VAD_MAX_THRESHOLD);
                VadStats stats = calculateVad(packets, vadThreshold);
                
                if (!stats.isSpeech) {
                    logger.info("Dropped buffer for user {}: mostly silence/noise ({}/{} speech frames, peakAmp={}, vad_threshold={})", 
                        displayName, stats.speechFrames, stats.totalFrames, stats.maxAmplitude, String.format("%.2f", vadThreshold));
                        
                    // VAD Lowering Logic
                    if (stats.maxAmplitude > 500 || packets.size() > 50) {
                        if (!session.userHasPassedVad.getOrDefault(userId, false)) {
                            int drops = session.userVadDroppedSequential.getOrDefault(userId, 0) + 1;
                            session.userVadDroppedSequential.put(userId, drops);
                            
                            if (drops >= 3) {
                                float newThreshold = Math.max(CaptionsConfig.VAD_MIN_THRESHOLD, vadThreshold - CaptionsConfig.VAD_STEP_DOWN);
                                session.userVadThresholds.put(userId, newThreshold);
                                session.userVadDroppedSequential.put(userId, 0);
                                logger.info("Lowered VAD threshold for new user {} to {}", displayName, String.format("%.2f", newThreshold));
                            }
                        }
                    }
                    return;
                }
                
                // Passed VAD
                session.userHasPassedVad.put(userId, true);

                // Wrap in OGG
                byte[] oggData = OggOpusWriter.write(packets);
                
                String lastText = session.lastUserText.get(userId);
                GroqClient.GroqResult result = groq.translateAudio(oggData, "audio.ogg", lastText, session.captionMode, displayName, vadThreshold);
                
                String text = result.text.trim();
                
                if (text.isEmpty()) {
                    // API Incremeting logic
                    float newThreshold = Math.min(CaptionsConfig.VAD_MAX_THRESHOLD, vadThreshold + CaptionsConfig.VAD_STEP_UP);
                    if (newThreshold != vadThreshold) {
                        session.userVadThresholds.put(userId, newThreshold);
                        logger.info("Increased VAD threshold for user {} to {} due to API feedback", displayName, String.format("%.2f", newThreshold));
                    }
                    return;
                }

                session.lastUserText.put(userId, text);
                addCaption(session, displayName, text, result.debugStr);

            } catch (Exception e) {
                logger.error("Error processing audio chunk for user {}: {}", displayName, e.getMessage(), e);
            }
        });
    }

    private static class VadStats {
        public final boolean isSpeech;
        public final int speechFrames;
        public final int totalFrames;
        public final int maxAmplitude;

        public VadStats(boolean isSpeech, int speechFrames, int totalFrames, int maxAmplitude) {
            this.isSpeech = isSpeech;
            this.speechFrames = speechFrames;
            this.totalFrames = totalFrames;
            this.maxAmplitude = maxAmplitude;
        }
    }

    private VadStats calculateVad(List<byte[]> packets, float vadThreshold) throws Exception {
        List<short[]> decodedFrames = new ArrayList<>();
        int maxAmplitude = 0;
        int totalValidFrames = 0;

        OpusDecoder decoder = new OpusDecoder(48000, 2);
        short[] pcm = new short[5760]; 
        int errorCount = 0;
        
        for (int i = 0; i < packets.size(); i++) {
            byte[] opus = packets.get(i);
            
            if (opus == null || opus.length < 5) {
                continue;
            }

            try {
                int samplesPerChannel = decoder.decode(opus, 0, opus.length, pcm, 0, 2880, false);
                if (samplesPerChannel > 0) {
                    totalValidFrames++;
                    short[] monoPcm = new short[CaptionsConfig.VAD_FRAME_SIZE];
                    for (int s = 0; s < Math.min(samplesPerChannel, CaptionsConfig.VAD_FRAME_SIZE); s++) {
                        short val = (short) ((pcm[s * 2] + pcm[s * 2 + 1]) / 2);
                        monoPcm[s] = val;
                        maxAmplitude = Math.max(maxAmplitude, Math.abs(val));
                    }
                    decodedFrames.add(monoPcm);
                }
            } catch (OpusException e) {
                errorCount++;
                // Only log individual packet errors if the error rate starts looking serious
                double errorRate = (double) errorCount / packets.size();
                if (errorRate > CaptionsConfig.MAX_OPUS_ERROR_PERCENTAGE || errorCount == 1) {
                    StringBuilder hex = new StringBuilder();
                    for (int b = 0; b < Math.min(opus.length, 8); b++) {
                        hex.append(String.format("%02X ", opus[b]));
                    }
                    
                    String tocInfo = "unknown";
                    if (opus.length > 0) {
                        int toc = opus[0] & 0xFF;
                        int config = (toc >> 3) & 0x1F;
                        int s = (toc >> 2) & 1;
                        int c = toc & 3;
                        tocInfo = String.format("TOC[config=%d, s=%d, c=%d]", config, s, c);
                    }

                    if (errorCount == 1) {
                        logger.debug("First Opus decoder error in chunk (packet {}/{}): {}", i, packets.size(), e.getMessage());
                    } else {
                        logger.warn("Opus decoder error rate high ({}/{} - {}%): Last error at packet {}: {}. Hex(8): {} | {}", 
                            errorCount, packets.size(), (int)(errorRate * 100), i, e.getMessage(), hex.toString().trim(), tocInfo);
                    }
                }
            }
        }
        
        if (totalValidFrames == 0) return new VadStats(false, 0, 0, 0);
        
        // Dynamically adjust threshold based on volume
        float dynamicThreshold = CaptionsConfig.VAD_MAX_THRESHOLD;
        if (maxAmplitude < 300) {
            dynamicThreshold = 0.1f;
        } else if (maxAmplitude < 1000) {
            dynamicThreshold = 0.2f;
        } else if (maxAmplitude < 3000) {
            dynamicThreshold = 0.3f;
        }

        int speechFrames = 0;
        try (TenVad vad = new TenVad(CaptionsConfig.VAD_FRAME_SIZE, dynamicThreshold)) {
            for (short[] frame : decodedFrames) {
                TenVad.VadResult res = vad.process(frame);
                if (res.isSpeech) speechFrames++;
            }
        }
        
        boolean isSpeech = (double) speechFrames / totalValidFrames >= CaptionsConfig.MIN_SPEECH_PERCENTAGE;
        return new VadStats(isSpeech, speechFrames, totalValidFrames, maxAmplitude);
    }

    private void addCaption(VoiceSession session, String displayName, String text, String debugStr) {
        synchronized (session.userLogs) {
            if (!sessions.containsKey(session.guildId)) return;

            String escapedText = MarkdownSanitizer.escape(text);
            String line = String.format("**%s**: %s", displayName, escapedText);
            session.userLogs.add(line);
            while (session.userLogs.size() > 10) {
                session.userLogs.remove(0);
            }
            
            String title = "whisper-large-v3 (English)";
            if ("transcribe".equals(session.captionMode)) title = "whisper-large-v3-turbo (Transcription)";
            else if ("korean".equals(session.captionMode)) title = "whisper-large-v3 (Korean)";
            else if ("arabic".equals(session.captionMode)) title = "whisper-large-v3 (Arabic)";

            String content = String.join("\n", session.userLogs);
            EmbedBuilder eb = new EmbedBuilder()
                    .setTitle(title)
                    .setDescription(content)
                    .setColor(Color.GREEN)
                    .setFooter(debugStr.length() > 2048 ? debugStr.substring(0, 2045) + "..." : debugStr);

            MessageChannel channel = jda.getChannelById(MessageChannel.class, session.textChannelId);
            if (channel != null) {
                channel.getHistoryAfter(session.embedMsgId, 6).queue(history -> {
                    // Check if session still active after async fetch
                    if (!sessions.containsKey(session.guildId)) return;

                    if (history.getRetrievedHistory().size() > 5) {
                        channel.deleteMessageById(session.embedMsgId).queue(null, err -> {});
                        channel.sendMessageEmbeds(eb.build())
                                .setComponents(ActionRow.of(createSelectionMenu(session.guildId, session.captionMode)))
                                .setSuppressedNotifications(true)
                                .queue(
                                    msg -> {
                                        // Final check: if user turned off captions while we were resending
                                        if (!sessions.containsKey(session.guildId)) {
                                            msg.delete().queue(null, err -> {});
                                            return;
                                        }
                                        session.embedMsgId = msg.getId();
                                    },
                                    err -> logger.error("Failed to send new captions message for {}: {}", displayName, err.getMessage())
                                );
                    } else {
                        channel.editMessageEmbedsById(session.embedMsgId, eb.build()).queue(
                            null,
                            err -> logger.error("Failed to edit captions message for {}: {}", displayName, err.getMessage())
                        );
                    }
                }, err -> logger.error("Failed to fetch message history: {}", err.getMessage()));
            } else {
                logger.error("Failed to resolve channel ID {} as MessageChannel", session.textChannelId);
            }
        }
    }

    private StringSelectMenu createSelectionMenu(String guildId, String selected) {
        return StringSelectMenu.create("caption_mode_" + guildId)
                .addOptions(
                        SelectOption.of("Transcribe", "transcribe").withDescription("whisper-large-v3-turbo").withDefault("transcribe".equals(selected)),
                        SelectOption.of("English", "english").withDescription("whisper-large-v3, english target").withDefault("english".equals(selected)),
                        SelectOption.of("Korean", "korean").withDescription("whisper-large-v3, korean target").withDefault("korean".equals(selected)),
                        SelectOption.of("Arabic", "arabic").withDescription("whisper-large-v3, arabic target").withDefault("arabic".equals(selected))
                )
                .build();
    }

    public void registerCommands() {
        logger.info("Registering /captions command...");
        jda.upsertCommand(
            Commands.slash("captions", "Manage real-time translated captions in voice channels")
                .addSubcommands(
                    new SubcommandData("on", "Start captions in your current voice channel"),
                    new SubcommandData("off", "Stop captions and leave the voice channel")
                )
        ).queue(
            success -> logger.info("Successfully registered /captions command"),
            error -> logger.error("Failed to register /captions command: {}", error.getMessage())
        );
    }
}
