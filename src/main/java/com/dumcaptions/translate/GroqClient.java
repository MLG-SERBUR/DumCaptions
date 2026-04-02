package com.dumcaptions.translate;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.dumcaptions.captions.CaptionsConfig;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class GroqClient {
    private static final Logger logger = LoggerFactory.getLogger(GroqClient.class);
    private final String apiKey;
    private final String model;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final AtomicLong lastReqTime = new AtomicLong(0);

    public GroqClient(String apiKey, String model) {
        this.apiKey = apiKey;
        this.model = (model == null || model.isEmpty()) ? "whisper-large-v3-turbo" : model;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
        this.objectMapper = new ObjectMapper();
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public static class GroqResult {
        public final String text;
        public final String debugStr;

        public GroqResult(String text, String debugStr) {
            this.text = text;
            this.debugStr = debugStr;
        }
    }

    public GroqResult translateAudio(byte[] audioData, String filename, String prompt, String mode, String userIdentifier, float vadThreshold) throws IOException {
        // --- RATE LIMITER ---
        long now = System.currentTimeMillis();
        long elapsed = now - lastReqTime.get();
        if (elapsed < CaptionsConfig.RATE_LIMIT_INTERVAL_MS) {
            long sleepTime = CaptionsConfig.RATE_LIMIT_INTERVAL_MS - elapsed;
            if (sleepTime > 1000) {
                logger.info("Rate limit: {}ms", sleepTime);
            }
            try {
                Thread.sleep(sleepTime);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        lastReqTime.set(System.currentTimeMillis());

        String targetUrl = "https://api.groq.com/openai/v1/audio/translations";
        String reqModel = this.model;
        String language = null;

        if ("transcribe".equals(mode)) {
            targetUrl = "https://api.groq.com/openai/v1/audio/transcriptions";
            reqModel = "whisper-large-v3-turbo";
        } else if ("english".equals(mode)) {
            targetUrl = "https://api.groq.com/openai/v1/audio/translations";
            reqModel = "whisper-large-v3";
        } else if ("korean".equals(mode)) {
            targetUrl = "https://api.groq.com/openai/v1/audio/transcriptions";
            reqModel = "whisper-large-v3";
            language = "ko";
        } else if ("arabic".equals(mode)) {
            targetUrl = "https://api.groq.com/openai/v1/audio/transcriptions";
            reqModel = "whisper-large-v3";
            language = "ar";
        }

        // --- MULTIPART REQUEST ---
        RequestBody fileBody = RequestBody.create(audioData, MediaType.parse("audio/ogg"));
        MultipartBody.Builder rbBuilder = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", filename, fileBody)
                .addFormDataPart("model", reqModel)
                .addFormDataPart("response_format", "verbose_json")
                .addFormDataPart("temperature", "0");
                
        if (language != null) {
            rbBuilder.addFormDataPart("language", language);
        }

        MultipartBody requestBody = rbBuilder.build();

        Request request = new Request.Builder()
                .url(targetUrl)
                .post(requestBody)
                .addHeader("Authorization", "Bearer " + apiKey)
                .build();

        logger.info("[{}] {} bytes thr={}", userIdentifier, audioData.length, String.format("%.2f", vadThreshold));

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "No body";
                logger.error("Groq API Request failed for model {}. Code: {}, Body: {}", model, response.code(), errorBody);
                throw new IOException("Groq API error: " + response.code() + " - " + errorBody);
            }

            GroqVerboseResponse result = objectMapper.readValue(response.body().byteStream(), GroqVerboseResponse.class);
            return processSegments(result, userIdentifier);
        }
    }

    /**
     * Build a debug string for a single segment's quality metrics.
     */
    private String segmentDebug(GroqSegment seg) {
        String noSpeechPart = String.format("no_speech: %.2f", seg.noSpeechProb);
        if (seg.noSpeechProb > 0.2) noSpeechPart = "**" + noSpeechPart + "**";

        String compPart = String.format("comp: %.2f", seg.compressionRatio);
        if (seg.compressionRatio > 2.0) compPart = "**" + compPart + "**";

        return String.format("%s, %s, logprob: %.2f", noSpeechPart, compPart, seg.avgLogprob);
    }

    private GroqResult processSegments(GroqVerboseResponse result, String userIdentifier) {
        List<GroqSegment> validSegments = new ArrayList<>();
        List<String> mergedSegmentsList = new ArrayList<>();
        List<String> blacklistedTexts = new ArrayList<>();

        if (result.segments != null) {
            for (GroqSegment seg : result.segments) {
                // Rule A: High no_speech probability
                if (seg.noSpeechProb > 0.2) {
                    logger.info("[{}] no_speech: '{}' | {}", userIdentifier, seg.text, segmentDebug(seg));
                    continue;
                }

                // Rule B: High compression ratio (hallucination loops)
                if (seg.compressionRatio > 2.0) {
                    logger.info("[{}] high_comp: '{}' | {}", userIdentifier, seg.text, segmentDebug(seg));
                    continue;
                }

                // Rule C: Hallucination filter
                String cleanedText = filterHallucinations(seg.text);
                if (cleanedText == null || cleanedText.isEmpty()) {
                    logger.info("[{}] blacklist: '{} | {}'", userIdentifier, seg.text, segmentDebug(seg));
                    blacklistedTexts.add(seg.text.trim());
                    continue;
                }

                seg.text = cleanedText;

                // Handle overlaps
                if (validSegments.isEmpty()) {
                    validSegments.add(seg);
                } else {
                    GroqSegment lastSeg = validSegments.get(validSegments.size() - 1);
                    double overlapStart = Math.max(lastSeg.start, seg.start);
                    double overlapEnd = Math.min(lastSeg.end, seg.end);
                    double overlapDuration = Math.max(0, overlapEnd - overlapStart);

                    double lastDuration = lastSeg.end - lastSeg.start;
                    double segDuration = seg.end - seg.start;
                    double minDuration = Math.min(lastDuration, segDuration);

                    if (minDuration > 0 && overlapDuration >= 0.5 * minDuration) {
                        if (seg.text.trim().length() > lastSeg.text.trim().length()) {
                            logger.info("[{}] overlap: '{}' → '{}'", userIdentifier, lastSeg.text, seg.text);
                            mergedSegmentsList.add(lastSeg.text);
                            validSegments.set(validSegments.size() - 1, seg);
                        }
                    } else {
                        validSegments.add(seg);
                    }
                }
            }
        }

        StringBuilder finalText = new StringBuilder();
        for (GroqSegment seg : validSegments) {
            if (finalText.length() > 0) finalText.append(" ");
            finalText.append(seg.text);
        }

        int count = validSegments.size();
        int mergedCount = mergedSegmentsList.size();
        String resultText = finalText.toString().trim();

        if (count > 0) {
            logger.info("[{}] \"{}\"", userIdentifier, resultText);
            for (int i = 0; i < validSegments.size(); i++) {
                GroqSegment seg = validSegments.get(i);
                logger.info("[{}]   #{} [{}-{}s] '{}' | {}",
                        userIdentifier, (i + 1), String.format("%.2f", seg.start), String.format("%.2f", seg.end),
                        seg.text, segmentDebug(seg));
            }
        }

        if (mergedCount > 0) {
            logger.info("[{}] merged {}: {}", userIdentifier, mergedCount, String.join(" | ", mergedSegmentsList));
        }

        if (!blacklistedTexts.isEmpty()) {
            logger.info("[{}] **Blacklisted: {}**", userIdentifier, String.join(", ", blacklistedTexts));
        }

        return new GroqResult(resultText, "");
    }

    private String filterHallucinations(String text) {
        if (text == null) return null;
        String lower = text.trim().toLowerCase()
                .replaceAll("[.!?,]", "")
                .trim();

        for (String hallucination : CaptionsConfig.HALLUCINATION_BLACKSET) {
            if (lower.equals(hallucination)) {
                return null;
            }
        }
        return text;
    }

    // --- INNER CLASSES FOR JSON ---
    public static class GroqVerboseResponse {
        @JsonProperty("text") public String text;
        @JsonProperty("segments") public List<GroqSegment> segments;
    }

    public static class GroqSegment {
        @JsonProperty("id") public int id;
        @JsonProperty("start") public double start;
        @JsonProperty("end") public double end;
        @JsonProperty("text") public String text;
        @JsonProperty("avg_logprob") public double avgLogprob;
        @JsonProperty("compression_ratio") public double compressionRatio;
        @JsonProperty("no_speech_prob") public double noSpeechProb;
    }
}
