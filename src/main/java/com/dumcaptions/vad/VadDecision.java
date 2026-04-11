package com.dumcaptions.vad;

public class VadDecision {
    public final boolean isSpeech;
    public final String rule;

    public VadDecision(boolean isSpeech, String rule) {
        this.isSpeech = isSpeech;
        this.rule = rule;
    }
}
