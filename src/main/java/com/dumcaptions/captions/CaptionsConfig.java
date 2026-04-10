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
    public static final long NATURAL_SILENCE_THRESHOLD_MS = 1000; // Natural break after 1s silence
    public static final long HARD_CUTOFF_THRESHOLD_MS = 10000;   // Maximum buffer length of 10s
    
    // TEN-VAD parameters
    public static final int VAD_FRAME_SIZE = 960;               // 20ms at 48kHz
    public static final float VAD_THRESHOLD = 0.45f;             // Fixed threshold. 0.5f is default
    public static final double MIN_SPEECH_PERCENTAGE = 0.05;    // Drop if < 5% of frames are speech
    public static final int MIN_SPEECH_FRAMES = 2;              // Require at least 2 speech frames (transient guard)
    public static final float HIGH_CONFIDENCE_THRESHOLD = 0.5f; // Frames above this are "confident speech"
    public static final double MIN_HIGH_CONFIDENCE_FRAMES = 1;  // At least 1 high-confidence frame needed
    
    // Overlap management
    public static final int OVERLAP_PACKETS = 50;               // Fallback: 1 second (50 * 20ms)
    public static final int MIN_OVERLAP_PACKETS = 25;           // Minimum overlap: 0.5 seconds
    public static final double MAX_OVERLAP_MS = 1500;           // Maximum overlap to retain: 1.5s
    public static final double OVERLAP_SAFETY_MS = 500;         // Safety margin for word boundaries: 0.5s
    
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
