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

    public static class ShouldProcessResult {
        public final boolean shouldProcess;
        public final boolean isHardCutoff;
        public final boolean isStale;

        public ShouldProcessResult(boolean shouldProcess, boolean isHardCutoff, boolean isStale) {
            this.shouldProcess = shouldProcess;
            this.isHardCutoff = isHardCutoff;
            this.isStale = isStale;
        }
    }

    public synchronized ShouldProcessResult shouldProcess() {
        if (packets.isEmpty()) {
            return new ShouldProcessResult(false, false, false);
        }

        Instant now = Instant.now();
        long duration = java.time.Duration.between(firstPush, now).toMillis();
        long silence = java.time.Duration.between(lastPush, now).toMillis();

        // 1. Hard Cutoff
        if (duration > CaptionsConfig.HARD_CUTOFF_THRESHOLD_MS) {
            return new ShouldProcessResult(true, true, false);
        }

        // 2. Stale Data
        if (silence > CaptionsConfig.STALE_DATA_THRESHOLD_MS) {
            return new ShouldProcessResult(true, false, true);
        }

        // 3. Natural Silence
        if (silence > CaptionsConfig.NATURAL_SILENCE_THRESHOLD_MS) {
            return new ShouldProcessResult(true, false, false);
        }

        return new ShouldProcessResult(false, false, false);
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
}
