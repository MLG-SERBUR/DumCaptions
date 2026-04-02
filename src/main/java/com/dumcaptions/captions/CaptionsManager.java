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
    
    // Processing state
    private final java.util.concurrent.atomic.AtomicBoolean groqProcessingInFlight = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final BlockingQueue<GroqSubmission> groqQueue = new LinkedBlockingQueue<>();

    // Shaping constants
    private static final long AGING_BOOST_MS = 5000;  // Every 5s of waiting, boost priority by 1
    private static final int MAX_CONSECUTIVE_PER_TICK = 1; // Max submissions per buffer per tick (fairness)


    public CaptionsManager(JDA jda, GroqClient groq) {
        this.jda = jda;
        this.groq = groq;

        // Start the ticker for processing buffers and the groq queue
        scheduler.scheduleAtFixedRate(this::checkBuffers, 200, 200, TimeUnit.MILLISECONDS);
        scheduler.scheduleAtFixedRate(this::processGroqQueue, 200, 200, TimeUnit.MILLISECONDS);
    }

    public static class VoiceSession {
        public final String guildId;
        public final String textChannelId;
        public final Map<Long, AudioBuffer> userAudio = new ConcurrentHashMap<>();
        public final Map<Long, String> lastUserText = new ConcurrentHashMap<>();
        /** End timestamp (ms) of the last transcribed segment per user, for timestamp-driven overlap */
        public final Map<Long, Double> lastUserSegmentEnd = new ConcurrentHashMap<>();
        public final List<String> userLogs = new ArrayList<>();
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
            buf.push(BufferedOpusPacket.from(packet));
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

            // Dispatch all ready users to VAD analysis immediately
            boolean dispatched = false;
            for (QueueEntry entry : ready) {
                int subs = consecutiveSubs.getOrDefault(entry.userId, 0);
                if (subs >= MAX_CONSECUTIVE_PER_TICK) {
                    continue; // Skip this user for now, give others a turn
                }

                // Pop from buffer
                boolean isHard = entry.state.priority == AudioBuffer.Priority.HARD_CUTOFF;
                // Retrieve last segment end time for timestamp-driven overlap
                double lastEndMs = session.lastUserSegmentEnd.getOrDefault(entry.userId, -1.0);
                List<BufferedOpusPacket> packets = entry.buf.pop(isHard, lastEndMs, entry.state.duration);
                if (packets.size() >= 25) {
                    // Capture overlap duration from the buffer (set during pop())
                    double overlapMs = entry.buf.getLastRetainedOverlapMs();
                    
                    analyzeVadAndQueue(session, entry.userId, packets, overlapMs);
                    
                    consecutiveSubs.put(entry.userId, subs + 1);
                    dispatched = true;
                } else {
                    // Too small, clear without processing
                    consecutiveSubs.put(entry.userId, 0);
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

    private boolean isRateLimited() {
        return System.currentTimeMillis() < nextReqTime.get();
    }

    private void markRequestSent() {
        nextReqTime.set(System.currentTimeMillis() + CaptionsConfig.RATE_LIMIT_INTERVAL_MS);
    }

    private void processGroqQueue() {
        if (groqProcessingInFlight.get() || isRateLimited()) return;
        
        GroqSubmission submission = groqQueue.poll();
        if (submission == null) return;

        groqProcessingInFlight.set(true);
        audioExecutor.submit(() -> {
            try {
                // Now we are in the rate-limited transcription stage
                markRequestSent();

                // Wrap in OGG
                byte[] oggData = OggOpusWriter.write(submission.packets);
                
                String lastText = submission.session.lastUserText.get(submission.userId);
                String captionMode = submission.session.captionMode;
                GroqClient.GroqResult result = groq.translateAudio(oggData, "audio.ogg", lastText, captionMode, submission.displayName, CaptionsConfig.VAD_THRESHOLD);
                
                String text = result.text.trim();
                if (text.isEmpty()) {
                    logger.info("API empty for {} VAD: {}", submission.displayName, submission.stats.debugReason);
                    return;
                }

                // Overlap resolution
                String previousText = submission.session.lastUserText.get(submission.userId);
                String displayText = text;
                String overlapFooter = "";
                if (previousText != null && !previousText.isEmpty()) {
                    String overlapResult = resolveOverlap(previousText, text);
                    if (overlapResult == null) {
                        logger.info("OVERLAP SKIP {} (contained in last): '{}'", submission.displayName, text);
                        submission.session.lastUserText.put(submission.userId, previousText);
                        return;
                    }
                    displayText = overlapResult;
                    if (!displayText.equals(text) && !displayText.trim().isEmpty()) {
                        overlapFooter = String.format("overlap_trim: '%s'->'%s'", text.trim(), displayText.trim());
                        logger.info("OVERLAP TRIM {} '{}' -> '{}'", submission.displayName, text, displayText);
                    }
                }

                submission.session.lastUserText.put(submission.userId, displayText);
                submission.session.lastUserSegmentEnd.put(submission.userId, result.lastSegmentEndMs);
                
                addCaption(submission.session, submission.displayName, displayText, overlapFooter, submission.userId, submission.overlapMs);

            } catch (Exception e) {
                logger.error("Error in Groq worker for {}: {}", submission.displayName, e.getMessage(), e);
            } finally {
                groqProcessingInFlight.set(false);
            }
        });
    }

    private void analyzeVadAndQueue(VoiceSession session, long userId, List<BufferedOpusPacket> packets, double overlapMs) {
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

                PreparedPacketBatch batch = preparePacketBatch(packets);
                if (batch.hasIssues() && batch.shouldWarn()) {
                    logger.warn("Opus sanitize {}: {}", displayName, batch.summary());
                }

                // VAD Filtering - fixed threshold, zero-latency (not gated by rate limit)
                VadStats stats = calculateVad(batch, overlapMs);
                
                if (!stats.isSpeech) {
                    logger.info("VAD REJECT {}: {}", displayName, stats.debugReason);
                    return;
                }
                
                // Passed VAD - Queue for Groq transcription
                logger.info("VAD PASS {}: {}", displayName, stats.debugReason);
                groqQueue.offer(new GroqSubmission(session, userId, displayName, batch.validPackets, overlapMs, stats));

            } catch (Exception e) {
                logger.error("Error analyzing VAD for user {}: {}", displayName, e.getMessage(), e);
            }
        });
    }

    private static class GroqSubmission {
        final VoiceSession session;
        final long userId;
        final String displayName;
        final List<BufferedOpusPacket> packets;
        final double overlapMs;
        final VadStats stats;

        GroqSubmission(VoiceSession session, long userId, String displayName, List<BufferedOpusPacket> packets, double overlapMs, VadStats stats) {
            this.session = session;
            this.userId = userId;
            this.displayName = displayName;
            this.packets = packets;
            this.overlapMs = overlapMs;
            this.stats = stats;
        }
    }

    /**
     * VAD statistics with probability-based decision making.
     */
    private static class VadStats {
        public final boolean isSpeech;
        public final int speechFrames;         // frames above threshold
        public final int totalFrames;
        public final int maxAmplitude;
        public final float maxProbability;      // highest probability seen in buffer
        public final float avgSpeechProbability; // average probability of speech frames only
        public final int highConfidenceFrames;  // frames above HIGH_CONFIDENCE_THRESHOLD
        public final String debugReason;

        public VadStats(boolean isSpeech, int speechFrames, int totalFrames, int maxAmplitude,
                        float maxProbability, float avgSpeechProbability, int highConfidenceFrames,
                        String debugReason) {
            this.isSpeech = isSpeech;
            this.speechFrames = speechFrames;
            this.totalFrames = totalFrames;
            this.maxAmplitude = maxAmplitude;
            this.maxProbability = maxProbability;
            this.avgSpeechProbability = avgSpeechProbability;
            this.highConfidenceFrames = highConfidenceFrames;
            this.debugReason = debugReason;
        }
    }

    /**
     * Simplified VAD: single VAD instance, fixed threshold, probability-based decision.
     * 
     * Decision logic:
     * 1. At least MIN_SPEECH_FRAMES above threshold (transient guard)
     * 2. At least MIN_HIGH_CONFIDENCE_FRAMES above HIGH_CONFIDENCE_THRESHOLD (quality gate)
     * 3. Speech frames >= MIN_SPEECH_PERCENTAGE of total (sustained presence)
     */
    private VadStats calculateVad(PreparedPacketBatch batch, double overlapMs) throws Exception {
        List<short[]> decodedFrames = batch.decodedFrames;
        int totalValidFrames = decodedFrames.size();

        if (totalValidFrames == 0) {
            return new VadStats(false, 0, 0, batch.maxAmplitude, 0, 0, 0,
                    "REJECT: no valid frames, " + batch.summary());
        }
        
        // Single VAD instance with fixed threshold
        float maxProbability = 0f;
        float sumSpeechProbability = 0f;
        int speechFrames = 0;
        int highConfidenceFrames = 0;
        
        try (TenVad vad = new TenVad(CaptionsConfig.VAD_FRAME_SIZE, CaptionsConfig.VAD_THRESHOLD)) {
            for (short[] frame : decodedFrames) {
                TenVad.VadResult result = vad.process(frame);
                
                maxProbability = Math.max(maxProbability, result.probability);
                
                if (result.isSpeech) {
                    speechFrames++;
                    sumSpeechProbability += result.probability;
                    if (result.probability >= CaptionsConfig.HIGH_CONFIDENCE_THRESHOLD) {
                        highConfidenceFrames++;
                    }
                }
            }
        }
        
        float avgSpeechProbability = speechFrames > 0 ? sumSpeechProbability / speechFrames : 0f;
        double speechPercentage = (double) speechFrames / totalValidFrames;
        
        // Build debug reason
        StringBuilder debug = new StringBuilder();
        String framesPart = String.format("%d/%d (%.0f%%)", speechFrames, totalValidFrames, speechPercentage * 100);
        
        boolean hasEnoughSpeechFrames = speechFrames >= CaptionsConfig.MIN_SPEECH_FRAMES;
        boolean hasEnoughPercentage = speechPercentage >= CaptionsConfig.MIN_SPEECH_PERCENTAGE;
        boolean hasHighConfidence = highConfidenceFrames >= CaptionsConfig.MIN_HIGH_CONFIDENCE_FRAMES;
        
        // Mark failed checks for debugging
        String framesPartDebug = hasEnoughPercentage ? framesPart : "**" + framesPart + "**";
        debug.append(framesPartDebug);
        debug.append(String.format(", max_prob=%.2f, avg_prob=%.2f", maxProbability, avgSpeechProbability));
        debug.append(String.format(", hi_conf=%d", highConfidenceFrames));
        debug.append(String.format(", amp=%d", batch.maxAmplitude));
        debug.append(String.format(", thr=%.2f", CaptionsConfig.VAD_THRESHOLD));
        if (overlapMs > 0) {
            debug.append(String.format(", overlap=%.0fms", overlapMs));
        }
        debug.append(", ").append(batch.summary());
        
        // Final decision: need enough speech frames OR high confidence speech
        // The OR gate ensures that even a short buffer with clear speech gets through,
        // while the AND of frames+percentage guards against transients in longer buffers
        boolean isSpeech = hasEnoughSpeechFrames && hasEnoughPercentage && hasHighConfidence;
                
        return new VadStats(isSpeech, speechFrames, totalValidFrames, batch.maxAmplitude,
                           maxProbability, avgSpeechProbability, highConfidenceFrames,
                           debug.toString());
    }

    private PreparedPacketBatch preparePacketBatch(List<BufferedOpusPacket> packets) throws OpusException {
        List<BufferedOpusPacket> orderedPackets = new ArrayList<>(packets);
        Collections.sort(orderedPackets);

        List<BufferedOpusPacket> validPackets = new ArrayList<>();
        List<short[]> decodedFrames = new ArrayList<>();
        OpusDecoder decoder = new OpusDecoder(48000, 2);
        short[] pcm = new short[5760];

        int maxAmplitude = 0;
        int decodeErrorPackets = 0;
        int suspiciousPackets = 0;
        int duplicatePackets = 0;
        int outOfOrderPackets = 0;
        int missingPackets = 0;
        String firstDropDetail = null;

        int previousArrivalSequence = -1;
        for (BufferedOpusPacket packet : packets) {
            int sequence = packet.getSequence();
            if (previousArrivalSequence != -1 && sequence < previousArrivalSequence) {
                outOfOrderPackets++;
            }
            previousArrivalSequence = sequence;
        }

        int previousSortedSequence = -1;
        for (BufferedOpusPacket packet : orderedPackets) {
            int sequence = packet.getSequence();
            byte[] opus = packet.getOpusAudio();

            if (previousSortedSequence != -1) {
                if (sequence == previousSortedSequence) {
                    duplicatePackets++;
                    if (firstDropDetail == null) {
                        firstDropDetail = formatDropDetail(packet, "duplicate sequence");
                    }
                    continue;
                }
                if (sequence > previousSortedSequence + 1) {
                    missingPackets += sequence - previousSortedSequence - 1;
                }
            }
            previousSortedSequence = sequence;

            if (opus == null || opus.length == 0 || looksLikeCorruptPayload(opus)) {
                suspiciousPackets++;
                if (firstDropDetail == null) {
                    firstDropDetail = formatDropDetail(packet, "suspicious payload");
                }
                continue;
            }

            try {
                int samplesPerChannel = decoder.decode(opus, 0, opus.length, pcm, 0, 2880, false);
                if (samplesPerChannel <= 0) {
                    decodeErrorPackets++;
                    if (firstDropDetail == null) {
                        firstDropDetail = formatDropDetail(packet, "empty decode");
                    }
                    continue;
                }

                short[] monoPcm = new short[CaptionsConfig.VAD_FRAME_SIZE];
                for (int s = 0; s < Math.min(samplesPerChannel, CaptionsConfig.VAD_FRAME_SIZE); s++) {
                    short val = (short) ((pcm[s * 2] + pcm[s * 2 + 1]) / 2);
                    monoPcm[s] = val;
                    maxAmplitude = Math.max(maxAmplitude, Math.abs(val));
                }

                decodedFrames.add(monoPcm);
                validPackets.add(packet);
            } catch (OpusException e) {
                decodeErrorPackets++;
                if (firstDropDetail == null) {
                    firstDropDetail = formatDropDetail(packet, e.getMessage());
                }
            }
        }

        return new PreparedPacketBatch(
                validPackets,
                decodedFrames,
                packets.size(),
                decodeErrorPackets,
                suspiciousPackets,
                duplicatePackets,
                outOfOrderPackets,
                missingPackets,
                maxAmplitude,
                firstDropDetail);
    }

    private boolean looksLikeCorruptPayload(byte[] opus) {
        if (opus.length == 0) {
            return true;
        }

        int inspect = Math.min(opus.length, 8);
        for (int i = 0; i < inspect; i++) {
            if ((opus[i] & 0xFF) != 0xFF) {
                return false;
            }
        }
        return true;
    }

    private String formatDropDetail(BufferedOpusPacket packet, String reason) {
        byte[] opus = packet.getOpusAudio();
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < Math.min(opus.length, 8); i++) {
            hex.append(String.format("%02X ", opus[i]));
        }

        return String.format(
                "first_drop=seq=%d ts=%d reason=%s hex=%s toc=%s",
                packet.getSequence(),
                packet.getTimestamp(),
                reason,
                hex.toString().trim(),
                describeToc(opus));
    }

    private String describeToc(byte[] opus) {
        if (opus.length == 0) {
            return "unknown";
        }

        int toc = opus[0] & 0xFF;
        int config = (toc >> 3) & 0x1F;
        int s = (toc >> 2) & 1;
        int c = toc & 3;
        return String.format("TOC[config=%d, s=%d, c=%d]", config, s, c);
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

    private void addCaption(VoiceSession session, String displayName, String text, String debugStr, long currentUserId, double overlapMs) {
        String queueDebug = getQueueDebug(session, currentUserId);
        String overlapStr = String.format(", ovlp=%.1fs", overlapMs / 1000.0);
        String footerText = debugStr + overlapStr + queueDebug;

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
     * Detects and resolves prefix-suffix overlap between captions.
     *
     * Strategy:
     * 1. If current is fully contained in previous, return null (skip).
     * 2. If previous's suffix matches current's prefix, strip the overlap.
     *    Example: prev="...in bed dead", curr="In bed dead. You should get up." -> "You should get up."
     * 3. Otherwise, return current as-is.
     */
    private String resolveOverlap(String previous, String current) {
        String prevNorm = normalize(previous);
        String currNorm = normalize(current);

        // Case 1: Current is fully contained in previous (exact duplicate or subset)
        if (currNorm.equals(prevNorm) || prevNorm.contains(currNorm)) {
            return null; // Skip entirely
        }

        // Case 2: Suffix-prefix overlap detection
        // Check if the end of previous matches the beginning of current.
        // We try progressively shorter suffixes of the previous text,
        // looking for a match at the start of the current text.
        // Minimum overlap: 2 words (to avoid false positives on single words)
        int minOverlapWords = 2;
        String[] prevWords = prevNorm.split("\\s+");
        String[] currWords = currNorm.split("\\s+");

        for (int overlapLen = Math.min(prevWords.length, currWords.length); overlapLen >= minOverlapWords; overlapLen--) {
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
                // Found overlap — strip the overlapping prefix from current
                String stripped = stripPrefixWords(current, overlapLen);
                return stripped.isEmpty() ? null : stripped.trim();
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
     * Strips the first N words from a string.
     * Returns remaining text or empty string if all words removed.
     */
    private String stripPrefixWords(String text, int wordCount) {
        String trimmed = text.trim();
        int pos = 0;
        int wordsSkipped = 0;
        boolean inWord = false;

        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (Character.isWhitespace(c)) {
                if (inWord) inWord = false;
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
