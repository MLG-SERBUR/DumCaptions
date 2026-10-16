package com.dumcaptions.vad;

import com.dumcaptions.captions.CaptionsConfig;
import com.dumcaptions.captions.PreparedPacketBatch;

import java.util.List;

public class VadAnalyzer {
    private final VadDecisionStrategy decisionStrategy;

    public VadAnalyzer(VadMode mode) {
        this.decisionStrategy = switch (mode) {
            case ADAPTIVE -> new AdaptiveVadDecisionStrategy();
            case RATIO -> new RatioVadDecisionStrategy();
        };
    }

    public VadStats analyze(PreparedPacketBatch batch, double overlapMs) throws Exception {
        List<short[]> decodedFrames = batch.getDecodedFrames();
        int totalValidFrames = decodedFrames.size();

        if (totalValidFrames == 0) {
            return new VadStats(false, 0, 0, batch.getMaxAmplitude(), 0, 0, 0,
                    "mode=" + decisionStrategy.mode().configValue() + ", REJECT: no valid frames, " + batch.getSummary());
        }

        // Shadow energy metrics: per-frame RMS level and spread. Peak amp alone undervalues
        // soft sustained speech and overvalues single spikes; RMS mean/std lets us compare
        // bursty speech against flat sustained noise without changing the decision yet.
        // frameRms is reused below so speech-only level needs no second pass over PCM.
        double[] frameRms = new double[totalValidFrames];
        double sumRms = 0;
        double sumSqRms = 0;
        double maxRms = 0;
        for (int i = 0; i < decodedFrames.size(); i++) {
            short[] frame = decodedFrames.get(i);
            long sumSq = 0;
            for (short s : frame) {
                sumSq += (long) s * s;
            }
            double rms = frame.length > 0 ? Math.sqrt((double) sumSq / frame.length) : 0;
            frameRms[i] = rms;
            sumRms += rms;
            sumSqRms += rms * rms;
            if (rms > maxRms) {
                maxRms = rms;
            }
        }
        double rmsMean = sumRms / totalValidFrames;
        double rmsVariance = Math.max(0, sumSqRms / totalValidFrames - rmsMean * rmsMean);
        double rmsStd = Math.sqrt(rmsVariance);
        double crest = rmsMean > 1 ? batch.getMaxAmplitude() / rmsMean : 0;

        float maxProbability = 0f;
        float minSpeechProbability = 0f;
        float sumSpeechProbability = 0f;
        double sumSpeechRms = 0;
        int speechFrames = 0;
        int highConfidenceFrames = 0;
        int firstSpeechFrameIndex = -1;
        int lastSpeechFrameIndex = -1;
        int longestSpeechRun = 0;
        int currentSpeechRun = 0;
        int speechRuns = 0;

        try (TenVad vad = new TenVad(CaptionsConfig.VAD_FRAME_SIZE, CaptionsConfig.VAD_THRESHOLD)) {
            for (int i = 0; i < decodedFrames.size(); i++) {
                TenVad.VadResult result = vad.process(decodedFrames.get(i));

                maxProbability = Math.max(maxProbability, result.probability);

                if (result.isSpeech) {
                    speechFrames++;
                    sumSpeechProbability += result.probability;
                    sumSpeechRms += frameRms[i];
                    if (speechFrames == 1) {
                        minSpeechProbability = result.probability;
                    } else {
                        minSpeechProbability = Math.min(minSpeechProbability, result.probability);
                    }
                    if (result.probability >= CaptionsConfig.HIGH_CONFIDENCE_THRESHOLD) {
                        highConfidenceFrames++;
                    }
                    if (firstSpeechFrameIndex < 0) {
                        firstSpeechFrameIndex = i;
                    }
                    lastSpeechFrameIndex = i;
                    if (currentSpeechRun == 0) {
                        speechRuns++;
                    }
                    currentSpeechRun++;
                    longestSpeechRun = Math.max(longestSpeechRun, currentSpeechRun);
                } else {
                    currentSpeechRun = 0;
                }
            }
        }

        float avgSpeechProbability = speechFrames > 0 ? sumSpeechProbability / speechFrames : 0f;
        double rmsSpeechMean = speechFrames > 0 ? sumSpeechRms / speechFrames : 0;
        VadFrameSummary summary = new VadFrameSummary(
                totalValidFrames,
                speechFrames,
                batch.getMaxAmplitude(),
                maxProbability,
                avgSpeechProbability,
                highConfidenceFrames,
                firstSpeechFrameIndex,
                lastSpeechFrameIndex,
                longestSpeechRun,
                rmsMean,
                rmsStd,
                maxRms,
                crest,
                speechRuns,
                minSpeechProbability,
                rmsSpeechMean
        );

        VadDecision decision = decisionStrategy.decide(summary);
        String debugReason = buildDebugReason(summary, batch, overlapMs, decision);

        return new VadStats(decision.isSpeech, speechFrames, totalValidFrames, batch.getMaxAmplitude(),
                maxProbability, avgSpeechProbability, highConfidenceFrames, debugReason,
                rmsMean, rmsStd, maxRms, crest, speechRuns, minSpeechProbability, rmsSpeechMean);
    }

    private String buildDebugReason(VadFrameSummary summary, PreparedPacketBatch batch, double overlapMs,
                                    VadDecision decision) {
        boolean hasEnoughPercentage = summary.speechPercentage() >= CaptionsConfig.MIN_SPEECH_PERCENTAGE;
        String framesPart = String.format("%d/%d (%.0f%%)", summary.speechFrames, summary.totalFrames,
                summary.speechPercentage() * 100);

        StringBuilder debug = new StringBuilder();
        debug.append(hasEnoughPercentage ? framesPart : "**" + framesPart + "**");
        debug.append(", mode=").append(decisionStrategy.mode().configValue());
        debug.append(", rule=").append(decision.rule);
        debug.append(String.format(", max_prob=%.2f, avg_prob=%.2f", summary.maxProbability, summary.avgSpeechProbability));
        debug.append(String.format(", hi_conf=%d", summary.highConfidenceFrames));
        debug.append(String.format(", span=%d (%.0f%%)", summary.speechSpanFrames(), summary.speechSpanRatio() * 100));
        debug.append(String.format(", run=%d/%d, minp=%.2f", summary.longestSpeechRun, summary.speechRuns, summary.minSpeechProbability));
        debug.append(String.format(", amp=%d", batch.getMaxAmplitude()));
        debug.append(String.format(", rms=%.0f/%.0f/%.0f/%.0f, crest=%.1f",
                summary.rmsMean, summary.rmsStd, summary.rmsMax, summary.rmsSpeechMean, summary.crest));
        debug.append(String.format(", thr=%.2f", CaptionsConfig.VAD_THRESHOLD));
        if (overlapMs > 0) {
            debug.append(String.format(", overlap=%.0fms", overlapMs));
        }
        debug.append(", ").append(batch.getSummary());
        return debug.toString();
    }
}
