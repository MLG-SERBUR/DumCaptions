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
import java.util.regex.Pattern;

public class CaptionsManager extends ListenerAdapter {
    private static final Logger logger = LoggerFactory.getLogger(CaptionsManager.class);

    private final JDA jda;
    private final GroqClient groq;
    private final Map<String, VoiceSession> sessions = new ConcurrentHashMap<>(); // GuildID -> Session
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final ExecutorService audioExecutor = Executors.newFixedThreadPool(4);
    private final AtomicLong nextReqTime = new AtomicLong(0);

    // Per-guild queue fairness: track how many consecutive submissions per sessionId
    // Key = guildId, Value = map of userId -> consecutive count since last submission
    private final Map<String, Map<Long, Integer>> userConsecutiveSubmissions = new ConcurrentHashMap<>();

    // Shaping constants
    private static final long AGING_BOOST_MS = 5000;  // Every 5s of waiting, boost priority by 1
    private static final int MAX_CONSECUTIVE_PER_TICK = 1; // Max submissions per buffer per tick (fairness)

    // Overlap detection constants
    private static final int OVERLAP_WORD_COUNT = 3;  // Number of words to check for prefix/suffix overlap

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
        public final Map<Long, Double> userNoiseFloor = new ConcurrentHashMap<>();  // Track per-user noise floor
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

    /**
     * Check all active sessions for buffers that are ready to process.
     * Builds a shaped priority queue per session:
     *   - Hard-cutoff buffers always go first
     *   - Queue aging prevents starvation (older buffers get boosted)
     *   - Fairness shaping: a single talkative user won't monopolize the queue
     *     when others are also waiting
     */
    private void checkBuffers() {
        for (VoiceSession session : sessions.values()) {
            // Build a list of ready entries with their computed queue priority
            List<QueueEntry> ready = new ArrayList<>();
            Map<Long, Integer> consecutiveSubs = userConsecutiveSubmissions.computeIfAbsent(
                    session.guildId, k -> new ConcurrentHashMap<>());

            for (Map.Entry<Long, AudioBuffer> entry : session.userAudio.entrySet()) {
                long userId = entry.getKey();
                AudioBuffer buf = entry.getValue();
                AudioBuffer.ReadyState state = buf.getReadiness();
                if (state == null) continue;

                // Compute shaped priority: base priority + aging bonus
                int basePriority;
                if (state.priority == AudioBuffer.Priority.HARD_CUTOFF) {
                    basePriority = 100; // Always top
                } else {
                    // SILENCE priority: lower number = quieter/shorter buffer, but
                    // aging adds to this so long-waiting buffers rise
                    basePriority = 1;
                }
                long agingBonus = state.duration / AGING_BOOST_MS;
                int effectivePriority = basePriority + (int)agingBonus;

                ready.add(new QueueEntry(userId, buf, effectivePriority, state));
            }

            if (ready.isEmpty()) continue;

            // Sort: highest effective priority first, then break ties by userId (round-robin hint)
            ready.sort((a, b) -> {
                // Hard cutoff always wins
                if (a.state.priority == AudioBuffer.Priority.HARD_CUTOFF &&
                    b.state.priority != AudioBuffer.Priority.HARD_CUTOFF) return -1;
                if (b.state.priority == AudioBuffer.Priority.HARD_CUTOFF &&
                    a.state.priority != AudioBuffer.Priority.HARD_CUTOFF) return 1;

                // For non-hard-cutoff entries, compare aging: longer wait wins
                int cmp = Integer.compare(b.effectivePriority, a.effectivePriority);
                if (cmp != 0) return cmp;

                // Tie-break: prefer users with fewer consecutive submissions (fairness)
                return Integer.compare(
                    consecutiveSubs.getOrDefault(a.userId, 0),
                    consecutiveSubs.getOrDefault(b.userId, 0)
                );
            });

            // Dispatch one entry at a time, respecting rate limit and fairness.
            // We stop after dispatching one per tick to avoid one user monopolizing
            // when multiple users have ready buffers.
            boolean dispatched = false;
            for (QueueEntry entry : ready) {
                int subs = consecutiveSubs.getOrDefault(entry.userId, 0);
                if (subs >= MAX_CONSECUTIVE_PER_TICK) {
                    continue; // Skip this user for now, give others a turn
                }

                if (canRequest()) {
                    // Pop from buffer
                    boolean isHard = entry.state.priority == AudioBuffer.Priority.HARD_CUTOFF;
                    List<byte[]> packets = entry.buf.pop(isHard);
                    if (packets.size() >= 25) {
                        processChunk(session, entry.userId, packets);
                        consecutiveSubs.put(entry.userId, subs + 1);
                        dispatched = true;
                        break; // One dispatch per tick per session
                    } else {
                        // Too small, clear without processing
                        consecutiveSubs.put(entry.userId, 0);
                        continue;
                    }
                } else {
                    // Rate limited, don't dispatch, will try next tick
                    break;
                }
            }

            // If nothing was dispatched this tick (not due to rate limits),
            // reset the counter for fairness
            if (!dispatched) {
                boolean noRateLimit = nextReqTime.get() <= System.currentTimeMillis();
                if (noRateLimit) {
                    consecutiveSubs.replaceAll((uid, val) -> 0);
                }
            }
        }
    }

    // Helper record for the queue
    private static class QueueEntry {
        final long userId;
        final AudioBuffer buf;
        final int effectivePriority;
        final AudioBuffer.ReadyState state;

        QueueEntry(long userId, AudioBuffer buf, int effectivePriority, AudioBuffer.ReadyState state) {
            this.userId = userId;
            this.buf = buf;
            this.effectivePriority = effectivePriority;
            this.state = state;
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
                VadStats stats = calculateVad(packets, vadThreshold, session, userId);
                
                if (!stats.isSpeech) {
                    logger.info("VAD REJECT {}: {}", displayName, stats.debugReason);
                        
                    // VAD Lowering Logic
                    if (stats.maxAmplitude > 500 || packets.size() > 50) {
                        if (!session.userHasPassedVad.getOrDefault(userId, false)) {
                            int drops = session.userVadDroppedSequential.getOrDefault(userId, 0) + 1;
                            session.userVadDroppedSequential.put(userId, drops);
                            
                            if (drops >= 3) {
                                float newThreshold = Math.max(CaptionsConfig.VAD_MIN_THRESHOLD, vadThreshold - CaptionsConfig.VAD_STEP_DOWN);
                                session.userVadThresholds.put(userId, newThreshold);
                                session.userVadDroppedSequential.put(userId, 0);
                                logger.info("VAD threshold ↓ {} → {}", displayName, String.format("%.2f", newThreshold));
                            }
                        }
                    }
                    return;
                }
                
                // Passed VAD
                logger.info("VAD PASS {}: {}", displayName, stats.debugReason);
                session.userHasPassedVad.put(userId, true);

                // Wrap in OGG
                byte[] oggData = OggOpusWriter.write(packets);
                
                String lastText = session.lastUserText.get(userId);
                GroqClient.GroqResult result = groq.translateAudio(oggData, "audio.ogg", lastText, session.captionMode, displayName, vadThreshold);
                
                String text = result.text.trim();
                
                if (text.isEmpty()) {
                    // API returned empty - log debug info to console
                    logger.info("API empty for {} VAD: {} | Groq: {}", displayName, stats.debugReason, result.debugStr);
                    
                    // API Incrementing logic
                    float newThreshold = Math.min(CaptionsConfig.VAD_MAX_THRESHOLD, vadThreshold + CaptionsConfig.VAD_STEP_UP);
                    if (newThreshold != vadThreshold) {
                        session.userVadThresholds.put(userId, newThreshold);
                        logger.info("VAD threshold ↑ {} → {} (API)", displayName, String.format("%.2f", newThreshold));
                    }
                    return;
                }

                // Check for overlap with the previous caption from this user
                String previousText = session.lastUserText.get(userId);
                String displayText = text;
                if (previousText != null && !previousText.isEmpty()) {
                    String overlapResult = resolveOverlap(previousText, text);
                    if (overlapResult == null) {
                        // New text is fully contained in or identical to last caption — skip
                        logger.info("OVERLAP SKIP {} (contained in last): '{}'", displayName, text);
                        session.lastUserText.put(userId, previousText); // keep last as-is
                        return;
                    }
                    displayText = overlapResult;
                    if (!displayText.equals(text) && !displayText.isEmpty()) {
                        logger.info("OVERLAP TRIM {} '{}' -> '{}'", displayName, text, displayText);
                    }
                }

                session.lastUserText.put(userId, displayText);
                addCaption(session, displayName, displayText, result.debugStr, userId);

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
        public final double weightedSpeechScore;
        public final String debugReason;

        public VadStats(boolean isSpeech, int speechFrames, int totalFrames, int maxAmplitude, double weightedSpeechScore, String debugReason) {
            this.isSpeech = isSpeech;
            this.speechFrames = speechFrames;
            this.totalFrames = totalFrames;
            this.maxAmplitude = maxAmplitude;
            this.weightedSpeechScore = weightedSpeechScore;
            this.debugReason = debugReason;
        }
    }

    private VadStats calculateVad(List<byte[]> packets, float vadThreshold, VoiceSession session, long userId) throws Exception {
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
        
        if (totalValidFrames == 0) return new VadStats(false, 0, 0, 0, 0, "REJECT: no valid frames");
        
        // Update noise floor - track the quietest frames as likely noise
        // Use slow EMA so noise floor is stable and doesn't jump around
        double currentNoiseFloor = session.userNoiseFloor.getOrDefault(userId, 100.0);
        
        // Run VAD with hysteresis: harder to START, easier to CONTINUE
        // First pass: use higher threshold to find definite speech starts
        float startThreshold = Math.min(CaptionsConfig.VAD_MAX_THRESHOLD, vadThreshold + CaptionsConfig.HYSTERESIS_START_BONUS);
        
        // Track consecutive speech frames and per-frame results
        int maxConsecutiveSpeech = 0;
        int currentConsecutive = 0;
        int speechFramesHighThreshold = 0;
        int speechFramesLowThreshold = 0;
        double weightedScore = 0;
        List<Boolean> frameResults = new ArrayList<>();
        
        try (TenVad vadHigh = new TenVad(CaptionsConfig.VAD_FRAME_SIZE, startThreshold);
             TenVad vadLow = new TenVad(CaptionsConfig.VAD_FRAME_SIZE, Math.max(CaptionsConfig.VAD_MIN_THRESHOLD, vadThreshold + CaptionsConfig.HYSTERESIS_CONTINUE_BONUS))) {
            
            for (short[] frame : decodedFrames) {
                // High threshold for starting speech
                TenVad.VadResult resHigh = vadHigh.process(frame);
                // Low threshold for continuing speech (after we've detected speech starting)
                TenVad.VadResult resLow = vadLow.process(frame);
                
                boolean isSpeechStart = resHigh.isSpeech;
                boolean isSpeechContinue = resLow.isSpeech;
                
                // Speech is detected if:
                // 1. High threshold says speech (definite start), OR
                // 2. We're in a speech streak AND low threshold says speech (continuation)
                boolean isSpeech = isSpeechStart || (currentConsecutive > 0 && isSpeechContinue);
                
                frameResults.add(isSpeech);
                
                if (isSpeech) {
                    speechFramesLowThreshold++;
                    if (isSpeechStart) speechFramesHighThreshold++;
                    currentConsecutive++;
                    maxConsecutiveSpeech = Math.max(maxConsecutiveSpeech, currentConsecutive);
                    weightedScore += resLow.probability;
                } else {
                    currentConsecutive = 0;
                    // Track frame amplitude for noise floor update
                    // Only update noise floor with quiet, non-speech frames
                    double frameAmp = 0;
                    for (short s : frame) frameAmp = Math.max(frameAmp, Math.abs(s));
                    if (frameAmp < currentNoiseFloor * 3) {
                        // This frame is likely noise (not much louder than current noise floor)
                        currentNoiseFloor = currentNoiseFloor * (1 - CaptionsConfig.NOISE_FLOOR_ALPHA) + frameAmp * CaptionsConfig.NOISE_FLOOR_ALPHA;
                    }
                }
            }
        }
        
        session.userNoiseFloor.put(userId, currentNoiseFloor);
        
        // Normalize weighted score
        double normalizedScore = weightedScore / totalValidFrames;
        double rawSpeechPercentage = (double) speechFramesLowThreshold / totalValidFrames;
        
        // Build debug reason explaining the decision
        StringBuilder debug = new StringBuilder();
        boolean hasEnoughConsecutive = maxConsecutiveSpeech >= CaptionsConfig.MIN_CONSECUTIVE_FOR_TRIGGER;
        boolean hasEnoughSpeechPercentage = rawSpeechPercentage >= CaptionsConfig.MIN_SPEECH_PERCENTAGE;
        
        String framesPart = String.format("%d/%d (%.0f%%)", speechFramesLowThreshold, totalValidFrames, rawSpeechPercentage * 100);
        if (!hasEnoughSpeechPercentage) framesPart = "**" + framesPart + "**";
        debug.append(framesPart);

        String consecPart = String.format("consec=%d/%d", maxConsecutiveSpeech, CaptionsConfig.MIN_CONSECUTIVE_FOR_TRIGGER);
        if (!hasEnoughConsecutive) consecPart = "**" + consecPart + "**";
        debug.append(", ").append(consecPart);

        debug.append(String.format(", amp=%d, nf=%.0f", maxAmplitude, currentNoiseFloor));
        debug.append(String.format(", thr=%.2f", vadThreshold));
        
        // Final decision: need enough consecutive frames AND enough speech percentage
        // This filters out transient noises like bird chirps that aren't sustained
        boolean isSpeech = hasEnoughConsecutive && hasEnoughSpeechPercentage;
        
        return new VadStats(isSpeech, speechFramesLowThreshold, totalValidFrames, maxAmplitude, normalizedScore, debug.toString());
    }

    /**
     * Returns a debug string about currently queued buffers for the session.
     * Shows each queued user with their display name, buffer size (packet count),
     * and how long they've been waiting in seconds.
     */
    private String getQueueDebug(VoiceSession session, long currentUserId) {
        List<QueuedUserDebug> queued = new ArrayList<>();

        for (Map.Entry<Long, AudioBuffer> entry : session.userAudio.entrySet()) {
            long userId = entry.getKey();
            if (userId == currentUserId) continue;
            AudioBuffer buf = entry.getValue();
            AudioBuffer.ReadyState state = buf.getReadiness();
            if (state == null) continue;

            // Get display name for the queued user
            String name = "Unknown (" + userId + ")";
            try {
                Member member = jda.getGuildById(session.guildId).getMemberById(userId);
                if (member != null) {
                    name = member.getEffectiveName();
                } else {
                    User user = jda.getUserById(userId);
                    if (user != null) {
                        name = user.getEffectiveName();
                    }
                }
            } catch (Exception ignored) {}

            queued.add(new QueuedUserDebug(name, buf.getPacketCount(), state.duration));
        }

        if (queued.isEmpty()) return "";

        // Sort by duration ascending (earliest/next-to-last in queue first)
        queued.sort((a, b) -> Long.compare(a.waitingMs, b.waitingMs));

        StringBuilder sb = new StringBuilder(" | Q: ");
        for (int i = 0; i < queued.size(); i++) {
            if (i > 0) sb.append(", ");
            QueuedUserDebug q = queued.get(i);
            double seconds = q.waitingMs / 1000.0;
            sb.append(String.format("%s (%s, %.1fs)", q.name, q.packetCount + " pkts", seconds));
        }
        return sb.toString();
    }

    private static class QueuedUserDebug {
        final String name;
        final int packetCount;
        final long waitingMs;

        QueuedUserDebug(String name, int packetCount, long waitingMs) {
            this.name = name;
            this.packetCount = packetCount;
            this.waitingMs = waitingMs;
        }
    }

    private void addCaption(VoiceSession session, String displayName, String text, String debugStr, long currentUserId) {
        String queueDebug = getQueueDebug(session, currentUserId);
        String footerText = debugStr + queueDebug;

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
                    .setFooter(footerText);

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
                                    err -> logger.error("Failed to send new captions message for {}: {}", displayName, err.getMessage(), err)
                                );
                    } else {
                        channel.editMessageEmbedsById(session.embedMsgId, eb.build()).queue(
                            null,
                            err -> logger.error("Failed to edit captions message for {}: {}", displayName, err.getMessage(), err)
                        );
                    }
                }, err -> logger.error("Failed to fetch message history: {}", err.getMessage(), err));
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

    /**
     * Detects and resolves overlap between the previous caption and the current caption.
     *
     * Strategy:
     * 1. If the current text is fully contained within the previous text (or vice versa),
     *    return null to signal a full skip.
     * 2. Check if the last N words of the previous text match the first N words of the
     *    current text. If so, strip the overlapping prefix from the current text.
     * 3. If no overlap is detected, return the current text as-is.
     *
     * @param previous The previous caption text from the same user.
     * @param current  The new caption text from the same user.
     * @return The resolved text to display (with overlap stripped), or null if the entire
     *         current caption should be skipped.
     */
    private String resolveOverlap(String previous, String current) {
        String prevNorm = normalize(previous);
        String currNorm = normalize(current);

        // Case 1: Exact match or current is fully contained in previous
        if (currNorm.equals(prevNorm) || prevNorm.contains(currNorm)) {
            return null; // Skip entirely
        }

        // Case 2: Previous is contained in current — this is the re-statement case
        // e.g., prev="dead", curr="Whenever I join you you're lying down in bed dead"
        // Here we want to send the current as-is (it's a restatement/clarification)
        if (currNorm.contains(prevNorm)) {
            return current;
        }

        // Case 3: Suffix-prefix word overlap
        // e.g., prev="...in bed dead", curr="Laying down in bed dead."
        // Extract last N words of previous and first N words of current
        String[] prevWords = prevNorm.split("\\s+");
        String[] currWords = currNorm.split("\\s+");

        int maxOverlap = Math.min(Math.min(prevWords.length, currWords.length), OVERLAP_WORD_COUNT);

        for (int overlapLen = maxOverlap; overlapLen >= 1; overlapLen--) {
            // Build suffix of previous text with 'overlapLen' words
            StringBuilder prevSuffix = new StringBuilder();
            for (int i = prevWords.length - overlapLen; i < prevWords.length; i++) {
                if (prevSuffix.length() > 0) prevSuffix.append(" ");
                prevSuffix.append(prevWords[i]);
            }

            // Build prefix of current text with 'overlapLen' words
            StringBuilder currPrefix = new StringBuilder();
            for (int i = 0; i < overlapLen; i++) {
                if (currPrefix.length() > 0) currPrefix.append(" ");
                currPrefix.append(currWords[i]);
            }

            if (prevSuffix.toString().equals(currPrefix.toString())) {
                // Found overlap — strip it from current text
                // Calculate how many characters to skip from the beginning of current
                String stripped = stripLeadingWords(current, overlapLen);
                if (stripped != null && !stripped.trim().isEmpty()) {
                    return stripped.trim();
                } else {
                    return null; // Nothing left after stripping
                }
            }
        }

        // No overlap detected
        return current;
    }

    /**
     * Normalizes text for comparison: lowercase, trim, strip punctuation.
     */
    private String normalize(String text) {
        return text.toLowerCase().replaceAll("[^\\w\\s]", "").trim();
    }

    /**
     * Strips the first N words from a string, preserving remaining capitalization and punctuation.
     * Returns empty string if all words are stripped.
     */
    private String stripLeadingWords(String text, int wordCount) {
        String trimmed = text.trim();
        int pos = 0;
        int wordsSkipped = 0;
        boolean inWord = false;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (Character.isWhitespace(c)) {
                if (inWord) {
                    inWord = false;
                }
            } else {
                if (!inWord) {
                    wordsSkipped++;
                    if (wordsSkipped > wordCount) {
                        pos = i;
                        break;
                    }
                    inWord = true;
                }
            }
        }
        if (wordsSkipped <= wordCount) {
            return "";
        }
        return trimmed.substring(pos);
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
            error -> logger.error("Failed to register /captions command: {}", error.getMessage(), error)
        );
    }
}