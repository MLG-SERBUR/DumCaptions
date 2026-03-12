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
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.managers.AudioManager;
import io.github.jaredmdobson.concentus.OpusDecoder;
import io.github.jaredmdobson.concentus.OpusException;
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
        public String embedMsgId;

        public VoiceSession(String guildId, String textChannelId) {
            this.guildId = guildId;
            this.textChannelId = textChannelId;
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
                .setTitle("Translated Captions")
                .setDescription("Listening for voices...")
                .setColor(Color.GREEN)
                .setFooter("Powered by Groq (Large-Whisper-v3)");

        event.getChannel().sendMessageEmbeds(eb.build()).queue(msg -> {
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
        jda.getTextChannelById(session.textChannelId).deleteMessageById(session.embedMsgId).queue(
            null, 
            err -> logger.warn("Failed to delete captions message: {}", err.getMessage())
        );

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
            String username = "Unknown (" + userId + ")";
            try {
                User user = jda.getUserById(userId);
                if (user != null) username = user.getName();
                
                Member member = jda.getGuildById(session.guildId).getMemberById(userId);
                if (member != null && member.getNickname() != null) {
                    username = member.getNickname();
                }

                logger.info("[DEBUG] Processing audio chunk for {}: {} packets", username, packets.size());

                // VAD Filtering
                VadStats stats = calculateVad(packets);
                if (!stats.isSpeech) {
                    logger.info("[DEBUG] Dropped buffer for user {}: mostly silence/noise ({}/{} speech frames)", 
                        username, stats.speechFrames, stats.totalFrames);
                    return;
                }

                // Wrap in OGG
                byte[] oggData = OggOpusWriter.write(packets);
                logger.info("[DEBUG] Generated Ogg data: {} bytes for {}", oggData.length, username);
                
                String lastText = session.lastUserText.get(userId);
                logger.info("[DEBUG] Calling Groq with prompt length {}", lastText != null ? lastText.length() : 0);
                GroqClient.GroqResult result = groq.translateAudio(oggData, "audio.ogg", lastText);
                
                String text = result.text.trim();
                logger.info("[DEBUG] Groq result for {}: '{}'", username, text);
                logger.info("[DEBUG] Segment stats for {}: {}", username, result.debugStr);
                
                if (text.isEmpty()) {
                    logger.info("[DEBUG] Groq returned empty text for {}", username);
                    return;
                }

                session.lastUserText.put(userId, text);
                addCaption(session, username, text, result.debugStr);

            } catch (Exception e) {
                logger.error("Error processing audio chunk for user {}: {}", username, e.getMessage(), e);
            }
        });
    }

    private static class VadStats {
        public final boolean isSpeech;
        public final int speechFrames;
        public final int totalFrames;

        public VadStats(boolean isSpeech, int speechFrames, int totalFrames) {
            this.isSpeech = isSpeech;
            this.speechFrames = speechFrames;
            this.totalFrames = totalFrames;
        }
    }

    private VadStats calculateVad(List<byte[]> packets) throws Exception {
        try (TenVad vad = new TenVad(CaptionsConfig.VAD_FRAME_SIZE, CaptionsConfig.VAD_THRESHOLD)) {
            OpusDecoder decoder = new OpusDecoder(48000, 1);
            int speechFrames = 0;
            short[] pcm = new short[CaptionsConfig.VAD_FRAME_SIZE];
            
            for (byte[] opus : packets) {
                int samples = decoder.decode(opus, 0, opus.length, pcm, 0, CaptionsConfig.VAD_FRAME_SIZE, false);
                if (samples > 0) {
                    TenVad.VadResult res = vad.process(pcm);
                    if (res.isSpeech) speechFrames++;
                }
            }
            boolean isSpeech = (double) speechFrames / packets.size() >= CaptionsConfig.MIN_SPEECH_PERCENTAGE;
            return new VadStats(isSpeech, speechFrames, packets.size());
        } catch (OpusException e) {
            logger.error("Opus decoder error: {}", e.getMessage());
            return new VadStats(true, packets.size(), packets.size()); // Fallback
        }
    }

    private void addCaption(VoiceSession session, String username, String text, String debugStr) {
        synchronized (session.userLogs) {
            String line = String.format("**%s**: %s", username, text);
            session.userLogs.add(line);
            if (session.userLogs.size() > 15) {
                session.userLogs.remove(0);
            }
            
            String content = String.join("\n", session.userLogs);
            EmbedBuilder eb = new EmbedBuilder()
                    .setTitle("Groq (Large-Whisper-v3)")
                    .setDescription(content)
                    .setColor(Color.GREEN)
                    .setFooter(debugStr.length() > 2048 ? debugStr.substring(0, 2045) + "..." : debugStr);

            MessageChannel channel = jda.getChannelById(MessageChannel.class, session.textChannelId);
            if (channel != null) {
                channel.editMessageEmbedsById(session.embedMsgId, eb.build()).queue(
                    success -> logger.info("[DEBUG] Updated captions embed for {}", username),
                    err -> logger.error("Failed to edit captions message for {}: {}", username, err.getMessage())
                );
            } else {
                logger.error("[DEBUG] Failed to resolution channel ID {} as MessageChannel", session.textChannelId);
            }
        }
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
