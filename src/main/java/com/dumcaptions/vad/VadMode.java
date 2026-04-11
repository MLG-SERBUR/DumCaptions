package com.dumcaptions.vad;

import java.util.Locale;

public enum VadMode {
    RATIO,
    ADAPTIVE;

    public static VadMode fromConfig(String value) {
        if (value == null || value.isBlank()) {
            return RATIO;
        }

        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "adaptive", "adaptive_sparse", "sparse", "soft" -> ADAPTIVE;
            default -> RATIO;
        };
    }

    public String configValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
