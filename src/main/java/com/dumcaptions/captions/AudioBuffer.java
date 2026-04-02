package com.dumcaptions.captions;

import com.dumcaptions.captions.CaptionsConfig;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class AudioBuffer {
    private final long ssrc;
    private final List<BufferedOpusPacket> packets = new ArrayList<>();
    private Instant firstPush = null;
    private Instant lastPush = null;
    /** Duration of overlap packets retained (ms), set by pop(). */
    private double lastRetainedOverlapMs = 0;

    public AudioBuffer(long ssrc) {
        this.ssrc = ssrc;
    }

    public synchronized void push(BufferedOpusPacket opusPacket) {
        if (firstPush == null) {
            firstPush = Instant.now();
        }
        packets.add(opusPacket);
        lastPush = Instant.now();
    }

    /**
     * Returns the current state of this buffer for the queue to evaluate.
     * @return a ReadyState if this buffer should be processed, null otherwise.
     */
    public synchronized ReadyState getReadiness() {
        if (packets.isEmpty()) {
            return null;
        }

        Instant now = Instant.now();
        long duration = java.time.Duration.between(firstPush, now).toMillis();
        long silence = java.time.Duration.between(lastPush, now).toMillis();

        // Hard cutoff: buffer has been running too long, process immediately
        if (duration > CaptionsConfig.HARD_CUTOFF_THRESHOLD_MS) {
            return new ReadyState(Priority.HARD_CUTOFF, duration, silence);
        }

        // Silence-triggered: natural pause detected, ready to process
        if (silence > CaptionsConfig.NATURAL_SILENCE_THRESHOLD_MS) {
            return new ReadyState(Priority.SILENCE, duration, silence);
        }

        return null; // Still building
    }

    /**
     * Priority levels for the shaped queue. Higher ordinal = more urgent.
     */
    public enum Priority {
        SILENCE(0),   // Natural pause in speech
        QUEUED(1),    // Been waiting in queue
        HARD_CUTOFF(2); // Buffer exceeded max duration

        public final int level;
        Priority(int level) { this.level = level; }
    }

    public static class ReadyState {
        public final Priority priority;
        public final long duration;
        public final long silenceMs;

        public ReadyState(Priority priority, long duration, long silenceMs) {
            this.priority = priority;
            this.duration = duration;
            this.silenceMs = silenceMs;
        }
    }

    /**
     * Pops packets from the buffer for processing.
     * 
     * @param isHardCutoff whether this is a hard cutoff trigger
     * @param lastSegmentEndMs end timestamp (ms) of last transcribed segment, or -1 if unknown
     * @param bufferDurationMs total duration of this buffer in ms
     * @return list of packet groups to process
     */
    public synchronized List<BufferedOpusPacket> pop(boolean isHardCutoff, double lastSegmentEndMs, long bufferDurationMs) {
        List<BufferedOpusPacket> p = new ArrayList<>(packets);

        if (isHardCutoff && packets.size() > 1) {
            // Calculate how many packets to retain for overlap
            int retainCount;
            
            if (lastSegmentEndMs > 0 && bufferDurationMs > 0) {
                // Timestamp-driven overlap: retain only audio after the last segment ended,
                // plus a small overlap window for word boundary safety
                double untranscribedMs = Math.max(0, bufferDurationMs - lastSegmentEndMs);
                double overlapMs = untranscribedMs + CaptionsConfig.OVERLAP_SAFETY_MS;
                overlapMs = Math.min(overlapMs, CaptionsConfig.MAX_OVERLAP_MS); // Cap maximum overlap
                
                // Each Opus packet is ~20ms
                retainCount = (int) Math.ceil(overlapMs / 20.0);
                retainCount = Math.max(retainCount, CaptionsConfig.MIN_OVERLAP_PACKETS);
            } else {
                // Fallback: use fixed overlap
                retainCount = CaptionsConfig.OVERLAP_PACKETS;
            }
            
            // Safety: don't retain more than we have
            retainCount = Math.min(retainCount, packets.size() - 1);
            retainCount = Math.max(retainCount, 0);
            
            if (retainCount > 0) {
                List<BufferedOpusPacket> overlap = new ArrayList<>(packets.subList(packets.size() - retainCount, packets.size()));
                packets.clear();
                packets.addAll(overlap);
                // Reset firstPush to reflect the retained audio duration
                firstPush = Instant.now().minusMillis(retainCount * 20L);
                lastRetainedOverlapMs = retainCount * 20;
            } else {
                packets.clear();
                firstPush = null;
                lastPush = null;
                lastRetainedOverlapMs = 0;
            }
        } else {
            packets.clear();
            firstPush = null;
            lastPush = null;
            lastRetainedOverlapMs = 0;
        }

        return p;
    }
    
    public long getSsrc() {
        return ssrc;
    }

    /**
     * Returns the current number of buffered packets.
     */
    public synchronized int getPacketCount() {
        return packets.size();
    }

    /**
     * Returns the duration (ms) of overlap packets retained during the last pop() call.
     */
    public synchronized double getLastRetainedOverlapMs() {
        return lastRetainedOverlapMs;
    }
}
