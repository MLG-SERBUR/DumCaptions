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
    // Shadow energy metrics for tuning (peak amp alone undervalues soft sustained speech).
    public final double rmsMean;
    public final double rmsStd;
    public final double rmsMax;
    public final double crest;
    // Speech-structure signals: burst count, weakest speech frame, speech-only level.
    // Whole-buffer RMS dilutes in long silence-padded buffers; rmsSpeechMean measures
    // the actual voice level. runs/minProb separate clustered utterances from spread speech.
    public final int speechRuns;
    public final float minSpeechProbability;
    public final double rmsSpeechMean;

    public VadFrameSummary(int totalFrames, int speechFrames, int maxAmplitude, float maxProbability,
                           float avgSpeechProbability, int highConfidenceFrames,
                           int firstSpeechFrameIndex, int lastSpeechFrameIndex, int longestSpeechRun,
                           double rmsMean, double rmsStd, double rmsMax, double crest,
                           int speechRuns, float minSpeechProbability, double rmsSpeechMean) {
        this.totalFrames = totalFrames;
        this.speechFrames = speechFrames;
        this.maxAmplitude = maxAmplitude;
        this.maxProbability = maxProbability;
        this.avgSpeechProbability = avgSpeechProbability;
        this.highConfidenceFrames = highConfidenceFrames;
        this.firstSpeechFrameIndex = firstSpeechFrameIndex;
        this.lastSpeechFrameIndex = lastSpeechFrameIndex;
        this.longestSpeechRun = longestSpeechRun;
        this.rmsMean = rmsMean;
        this.rmsStd = rmsStd;
        this.rmsMax = rmsMax;
        this.crest = crest;
        this.speechRuns = speechRuns;
        this.minSpeechProbability = minSpeechProbability;
        this.rmsSpeechMean = rmsSpeechMean;
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
