package com.dumcaptions.vad;

public class VadFrameSummary {
    public final int totalFrames;
    public final int speechFrames;
    public final int maxAmplitude;
    public final float maxProbability;
    public final float avgSpeechProbability;
    public final int highConfidenceFrames;
    public final int firstSpeechFrameIndex;
    public final int lastSpeechFrameIndex;
    public final int longestSpeechRun;

    public VadFrameSummary(int totalFrames, int speechFrames, int maxAmplitude, float maxProbability,
                           float avgSpeechProbability, int highConfidenceFrames,
                           int firstSpeechFrameIndex, int lastSpeechFrameIndex, int longestSpeechRun) {
        this.totalFrames = totalFrames;
        this.speechFrames = speechFrames;
        this.maxAmplitude = maxAmplitude;
        this.maxProbability = maxProbability;
        this.avgSpeechProbability = avgSpeechProbability;
        this.highConfidenceFrames = highConfidenceFrames;
        this.firstSpeechFrameIndex = firstSpeechFrameIndex;
        this.lastSpeechFrameIndex = lastSpeechFrameIndex;
        this.longestSpeechRun = longestSpeechRun;
    }

    public double speechPercentage() {
        if (totalFrames == 0) {
            return 0;
        }
        return (double) speechFrames / totalFrames;
    }

    public int speechSpanFrames() {
        if (speechFrames == 0 || firstSpeechFrameIndex < 0 || lastSpeechFrameIndex < 0) {
            return 0;
        }
        return (lastSpeechFrameIndex - firstSpeechFrameIndex) + 1;
    }

    public double speechSpanRatio() {
        if (totalFrames == 0) {
            return 0;
        }
        return (double) speechSpanFrames() / totalFrames;
    }
}
