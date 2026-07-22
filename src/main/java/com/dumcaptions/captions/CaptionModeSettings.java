package com.dumcaptions.captions;

/** Mode-specific behavior kept outside shared caption pipeline logic. */
public final class CaptionModeSettings {
    private static final CaptionModeSettings DEFAULT = new CaptionModeSettings(1000, 10000, 3000, false);
    private static final CaptionModeSettings SPANISH = new CaptionModeSettings(3000, 20000, 6000, true);

    private final long naturalSilenceThresholdMs;
    private final long hardCutoffThresholdMs;
    private final long requestCooldownMs;
    private final boolean includesEnglishTranslation;

    private CaptionModeSettings(long naturalSilenceThresholdMs, long hardCutoffThresholdMs,
                                long requestCooldownMs, boolean includesEnglishTranslation) {
        this.naturalSilenceThresholdMs = naturalSilenceThresholdMs;
        this.hardCutoffThresholdMs = hardCutoffThresholdMs;
        this.requestCooldownMs = requestCooldownMs;
        this.includesEnglishTranslation = includesEnglishTranslation;
    }

    public static CaptionModeSettings forMode(String captionMode) {
        return "spanish".equals(captionMode) ? SPANISH : DEFAULT;
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

    public boolean includesEnglishTranslation() {
        return includesEnglishTranslation;
    }
}
