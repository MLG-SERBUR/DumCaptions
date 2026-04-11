package com.dumcaptions.vad;

import com.dumcaptions.captions.CaptionsConfig;

import java.util.ArrayList;
import java.util.List;

public class AdaptiveVadDecisionStrategy implements VadDecisionStrategy {
    @Override
    public VadDecision decide(VadFrameSummary summary) {
        boolean classicPass = summary.speechFrames >= CaptionsConfig.MIN_SPEECH_FRAMES
                && summary.speechPercentage() >= CaptionsConfig.MIN_SPEECH_PERCENTAGE
                && summary.highConfidenceFrames >= CaptionsConfig.MIN_HIGH_CONFIDENCE_FRAMES;
        if (classicPass) {
            return new VadDecision(true, "ratio");
        }

        boolean sparseSpeechPass = summary.speechFrames >= CaptionsConfig.ADAPTIVE_MIN_SPEECH_FRAMES
                && summary.highConfidenceFrames >= CaptionsConfig.ADAPTIVE_MIN_HIGH_CONFIDENCE_FRAMES
                && summary.totalFrames >= CaptionsConfig.ADAPTIVE_MIN_TOTAL_FRAMES
                && summary.maxProbability >= CaptionsConfig.ADAPTIVE_MIN_MAX_PROBABILITY
                && summary.avgSpeechProbability >= CaptionsConfig.ADAPTIVE_MIN_AVG_SPEECH_PROBABILITY
                && summary.maxAmplitude >= CaptionsConfig.ADAPTIVE_MIN_AMPLITUDE
                && summary.speechSpanRatio() >= CaptionsConfig.ADAPTIVE_MIN_SPAN_RATIO;
        if (sparseSpeechPass) {
            return new VadDecision(true, "adaptive:sparse_span");
        }

        boolean singleFrameRescue = summary.speechFrames == 1
                && summary.totalFrames >= CaptionsConfig.ADAPTIVE_SINGLE_FRAME_MIN_TOTAL_FRAMES
                && summary.maxProbability >= CaptionsConfig.ADAPTIVE_SINGLE_FRAME_MIN_MAX_PROBABILITY
                && summary.avgSpeechProbability >= CaptionsConfig.ADAPTIVE_SINGLE_FRAME_MIN_AVG_SPEECH_PROBABILITY
                && summary.maxAmplitude >= CaptionsConfig.ADAPTIVE_MIN_AMPLITUDE;
        if (singleFrameRescue) {
            return new VadDecision(true, "adaptive:single_frame_long_buffer");
        }

        List<String> failedChecks = new ArrayList<>();
        if (summary.totalFrames < CaptionsConfig.ADAPTIVE_MIN_TOTAL_FRAMES) {
            failedChecks.add("short_buffer");
        }
        if (summary.maxProbability < CaptionsConfig.ADAPTIVE_MIN_MAX_PROBABILITY) {
            failedChecks.add("max_prob");
        }
        if (summary.avgSpeechProbability < CaptionsConfig.ADAPTIVE_MIN_AVG_SPEECH_PROBABILITY) {
            failedChecks.add("avg_prob");
        }
        if (summary.maxAmplitude < CaptionsConfig.ADAPTIVE_MIN_AMPLITUDE) {
            failedChecks.add("amp");
        }
        if (summary.speechFrames > 1 && summary.speechSpanRatio() < CaptionsConfig.ADAPTIVE_MIN_SPAN_RATIO) {
            failedChecks.add("span");
        }
        if (summary.speechFrames == 0) {
            failedChecks.add("no_speech");
        }
        if (failedChecks.isEmpty()) {
            failedChecks.add("no_rule");
        }
        return new VadDecision(false, "adaptive:" + String.join("+", failedChecks));
    }

    @Override
    public VadMode mode() {
        return VadMode.ADAPTIVE;
    }
}
