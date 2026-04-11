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

        float maxProbability = 0f;
        float sumSpeechProbability = 0f;
        int speechFrames = 0;
        int highConfidenceFrames = 0;
        int firstSpeechFrameIndex = -1;
        int lastSpeechFrameIndex = -1;
        int longestSpeechRun = 0;
        int currentSpeechRun = 0;

        try (TenVad vad = new TenVad(CaptionsConfig.VAD_FRAME_SIZE, CaptionsConfig.VAD_THRESHOLD)) {
            for (int i = 0; i < decodedFrames.size(); i++) {
                TenVad.VadResult result = vad.process(decodedFrames.get(i));

                maxProbability = Math.max(maxProbability, result.probability);

                if (result.isSpeech) {
                    speechFrames++;
                    sumSpeechProbability += result.probability;
                    if (result.probability >= CaptionsConfig.HIGH_CONFIDENCE_THRESHOLD) {
                        highConfidenceFrames++;
                    }
                    if (firstSpeechFrameIndex < 0) {
                        firstSpeechFrameIndex = i;
                    }
                    lastSpeechFrameIndex = i;
                    currentSpeechRun++;
                    longestSpeechRun = Math.max(longestSpeechRun, currentSpeechRun);
                } else {
                    currentSpeechRun = 0;
                }
            }
        }

        float avgSpeechProbability = speechFrames > 0 ? sumSpeechProbability / speechFrames : 0f;
        VadFrameSummary summary = new VadFrameSummary(
                totalValidFrames,
                speechFrames,
                batch.getMaxAmplitude(),
                maxProbability,
                avgSpeechProbability,
                highConfidenceFrames,
                firstSpeechFrameIndex,
                lastSpeechFrameIndex,
                longestSpeechRun
        );

        VadDecision decision = decisionStrategy.decide(summary);
        String debugReason = buildDebugReason(summary, batch, overlapMs, decision);

        return new VadStats(decision.isSpeech, speechFrames, totalValidFrames, batch.getMaxAmplitude(),
                maxProbability, avgSpeechProbability, highConfidenceFrames, debugReason);
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
        debug.append(String.format(", run=%d", summary.longestSpeechRun));
        debug.append(String.format(", amp=%d", batch.getMaxAmplitude()));
        debug.append(String.format(", thr=%.2f", CaptionsConfig.VAD_THRESHOLD));
        if (overlapMs > 0) {
            debug.append(String.format(", overlap=%.0fms", overlapMs));
        }
        debug.append(", ").append(batch.getSummary());
        return debug.toString();
    }
}
