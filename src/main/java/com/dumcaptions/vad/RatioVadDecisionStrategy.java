package com.dumcaptions.vad;

import com.dumcaptions.captions.CaptionsConfig;

import java.util.ArrayList;
import java.util.List;

public class RatioVadDecisionStrategy implements VadDecisionStrategy {
    @Override
    public VadDecision decide(VadFrameSummary summary) {
        boolean hasEnoughSpeechFrames = summary.speechFrames >= CaptionsConfig.MIN_SPEECH_FRAMES;
        boolean hasEnoughPercentage = summary.speechPercentage() >= CaptionsConfig.MIN_SPEECH_PERCENTAGE;
        boolean hasHighConfidence = summary.highConfidenceFrames >= CaptionsConfig.MIN_HIGH_CONFIDENCE_FRAMES;

        boolean isSpeech = hasEnoughSpeechFrames && hasEnoughPercentage && hasHighConfidence;
        if (isSpeech) {
            return new VadDecision(true, "ratio");
        }

        List<String> failedChecks = new ArrayList<>();
        if (!hasEnoughSpeechFrames) {
            failedChecks.add("min_frames");
        }
        if (!hasEnoughPercentage) {
            failedChecks.add("min_pct");
        }
        if (!hasHighConfidence) {
            failedChecks.add("hi_conf");
        }
        return new VadDecision(false, "ratio:" + String.join("+", failedChecks));
    }

    @Override
    public VadMode mode() {
        return VadMode.RATIO;
    }
}
