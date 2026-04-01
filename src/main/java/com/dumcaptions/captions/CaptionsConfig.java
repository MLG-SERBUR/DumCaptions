package com.dumcaptions.captions;

import java.util.concurrent.TimeUnit;

/**
 * Centralized configuration for captions logic.
 * These values can be hardcoded here for easy modification of the logic later.
 */
public class CaptionsConfig {
    
    // Rate limit between Groq API requests (global)
    public static final long RATE_LIMIT_INTERVAL_MS = 3000;
    
    // Audio processing thresholds
    public static final long STALE_DATA_THRESHOLD_MS = 6000;    // Forced process after 6s silence
    public static final long NATURAL_SILENCE_THRESHOLD_MS = 1000; // Natural break after 1s silence
    public static final long HARD_CUTOFF_THRESHOLD_MS = 30000;   // Maximum length of 30s
    
    // TEN-VAD parameters
    public static final int VAD_FRAME_SIZE = 960;               // 20ms at 48kHz
    public static final float VAD_MAX_THRESHOLD = 0.5f;
    public static final float VAD_MIN_THRESHOLD = 0.05f;        // Lowered from 0.1 to catch softer speech
    public static final float VAD_STEP_UP = 0.05f;
    public static final float VAD_STEP_DOWN = 0.15f;            // More aggressive step down for soft users
    public static final double MIN_SPEECH_PERCENTAGE = 0.03;    // Lowered from 5% to 3% to catch soft speech
    
    // Per-user amplitude tracking
    public static final double AMPLITUDE_EMA_ALPHA = 0.1;       // Exponential moving average smoothing factor
    public static final int AMPLITUDE_SOFT_THRESHOLD = 2000;    // Below this is considered "soft" voice
    
    // Overlap management
    public static final int OVERLAP_PACKETS = 100;              // 2 seconds (100 * 20ms)
    
    // Hallucination filter strings
    public static final String[] HALLUCINATION_BLACKSET = {
        "thank you", "thanks", "bye", "you", "okay", "goodbye", "thanks for watching",
        "thank you for watching", "please subscribe", "subscribe",
        "subscribe to the channel", "thank you very much", "thanks guys",
        "i'll see you in the next one", "if you'd like to subscribe",
        "subtitles by the amara.org community", "i'm sorry"
    };

    // Opus Error handling
    public static final double MAX_OPUS_ERROR_PERCENTAGE = 0.10; // Only log if > 10% frames fail
}
