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
    // Shadow energy metrics, copied from VadFrameSummary for probe eligibility and logging.
    public final double rmsMean;
    public final double rmsStd;
    public final double rmsMax;
    public final double crest;
    public final int speechRuns;
    public final float minSpeechProbability;
    public final double rmsSpeechMean;

    public VadStats(boolean isSpeech, int speechFrames, int totalFrames, int maxAmplitude,
                    float maxProbability, float avgSpeechProbability, int highConfidenceFrames,
                    String debugReason) {
        this(isSpeech, speechFrames, totalFrames, maxAmplitude, maxProbability, avgSpeechProbability,
                highConfidenceFrames, debugReason, 0, 0, 0, 0, 0, 0, 0);
    }

    public VadStats(boolean isSpeech, int speechFrames, int totalFrames, int maxAmplitude,
                    float maxProbability, float avgSpeechProbability, int highConfidenceFrames,
                    String debugReason, double rmsMean, double rmsStd, double rmsMax, double crest,
                    int speechRuns, float minSpeechProbability, double rmsSpeechMean) {
        this.isSpeech = isSpeech;
        this.speechFrames = speechFrames;
        this.totalFrames = totalFrames;
        this.maxAmplitude = maxAmplitude;
        this.maxProbability = maxProbability;
        this.avgSpeechProbability = avgSpeechProbability;
        this.highConfidenceFrames = highConfidenceFrames;
        this.debugReason = debugReason;
        this.rmsMean = rmsMean;
        this.rmsStd = rmsStd;
        this.rmsMax = rmsMax;
        this.crest = crest;
        this.speechRuns = speechRuns;
        this.minSpeechProbability = minSpeechProbability;
        this.rmsSpeechMean = rmsSpeechMean;
    }
}
