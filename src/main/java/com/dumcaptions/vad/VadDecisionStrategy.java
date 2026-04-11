package com.dumcaptions.vad;

public interface VadDecisionStrategy {
    VadDecision decide(VadFrameSummary summary);

    VadMode mode();
}
