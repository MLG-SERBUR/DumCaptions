package com.dumcaptions.captions;

import com.dumcaptions.captions.CaptionsConfig;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class AudioBuffer {
    private final long ssrc;
    private final List<byte[]> packets = new ArrayList<>();
    private Instant firstPush = null;
    private Instant lastPush = null;

    public AudioBuffer(long ssrc) {
        this.ssrc = ssrc;
    }

    public synchronized void push(byte[] opusData) {
        if (firstPush == null) {
            firstPush = Instant.now();
        }
        packets.add(opusData);
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

    public synchronized List<byte[]> pop(boolean isHardCutoff) {
        List<byte[]> p = new ArrayList<>(packets);

        if (isHardCutoff && packets.size() > CaptionsConfig.OVERLAP_PACKETS) {
            // Retain overlap
            List<byte[]> overlap = new ArrayList<>(packets.subList(packets.size() - CaptionsConfig.OVERLAP_PACKETS, packets.size()));
            packets.clear();
            packets.addAll(overlap);
            firstPush = Instant.now().minusMillis(2000); // Reset to 2s ago
        } else {
            packets.clear();
            firstPush = null;
            lastPush = null;
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
}
