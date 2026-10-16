package com.dumcaptions.captions;

/** Mode-specific behavior kept outside shared caption pipeline logic. */
public final class CaptionModeSettings {
    private static final CaptionModeSettings DEFAULT = new CaptionModeSettings(1000, 10000, 3000);

    private final long naturalSilenceThresholdMs;
    private final long hardCutoffThresholdMs;
    private final long requestCooldownMs;

    private CaptionModeSettings(long naturalSilenceThresholdMs, long hardCutoffThresholdMs,
                                long requestCooldownMs) {
        this.naturalSilenceThresholdMs = naturalSilenceThresholdMs;
        this.hardCutoffThresholdMs = hardCutoffThresholdMs;
        this.requestCooldownMs = requestCooldownMs;
    }

    public static CaptionModeSettings forMode(String captionMode) {
        return DEFAULT;
    }

    public long naturalSilenceThresholdMs() {
        return naturalSilenceThresholdMs;
    }

    public long hardCutoffThresholdMs() {
        return hardCutoffThresholdMs;
    }

    public long requestCooldownMs() {
        return requestCooldownMs;
    }

    /**
     * Returns copy with longer silence wait.
     * Used as temporary backpressure when Discord returns 429.
     * Hard cutoff, Groq cooldown unchanged.
     */
    public CaptionModeSettings withIncreasedSilence(long extraMs) {
        if (extraMs <= 0) return this;
        return new CaptionModeSettings(
                naturalSilenceThresholdMs + extraMs,
                hardCutoffThresholdMs,
                requestCooldownMs);
    }
}
