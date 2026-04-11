package com.dumcaptions.vad;

public class VadStats {
    public final boolean isSpeech;
    public final int speechFrames;
    public final int totalFrames;
    public final int maxAmplitude;
    public final float maxProbability;
    public final float avgSpeechProbability;
    public final int highConfidenceFrames;
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
