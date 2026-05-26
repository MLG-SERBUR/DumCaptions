package com.dumcaptions.translate.backends;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

public class TranslateAPI implements Translator {
    private final String apiKey;
    private final String baseUrl = "https://api.translateapi.ai/api/v1";
    private final OkHttpClient client;
    private final ObjectMapper mapper;

    public TranslateAPI(String apiKey) {
        this.apiKey = apiKey;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build();
        this.mapper = new ObjectMapper();
    }

    @Override
    public String getDisplayName() {
        return "TranslateAPI";
    }

    @Override
    public TranslateResponse translate(String text, String sourceLanguage) throws Exception {
        if (sourceLanguage == null || sourceLanguage.equals("unknown")) {
            sourceLanguage = "";
        }

        TranslateRequest req = new TranslateRequest(text, sourceLanguage, "en");
        String jsonBody = mapper.writeValueAsString(req);

        RequestBody body = RequestBody.create(jsonBody, MediaType.parse("application/json"));
        Request request = new Request.Builder()
                .url(baseUrl + "/translate/")
                .post(body)
                .addHeader("Authorization", "Bearer " + apiKey)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("API returned status: " + response.code() + ", body: " + response.body().string());
            }

            ApiResponse apiResponse = mapper.readValue(response.body().string(), ApiResponse.class);
            if (apiResponse.error != null && !apiResponse.error.isEmpty()) {
                throw new Exception("API error: " + apiResponse.error);
            }
            if (apiResponse.translatedText == null || apiResponse.translatedText.isEmpty()) {
                throw new Exception("Empty translation received");
            }

            return new TranslateResponse(apiResponse.translatedText, apiResponse.sourceLanguage, apiResponse.targetLanguage);
        }
    }

    private static class TranslateRequest {
        @JsonProperty("text")
        public String text;
        @JsonProperty("source_language")
        public String sourceLanguage;
        @JsonProperty("target_language")
        public String targetLanguage;

        public TranslateRequest(String text, String sourceLanguage, String targetLanguage) {
            this.text = text;
            this.sourceLanguage = sourceLanguage;
            this.targetLanguage = targetLanguage;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class ApiResponse {
        @JsonProperty("translated_text")
        public String translatedText;
        @JsonProperty("source_language")
        public String sourceLanguage;
        @JsonProperty("target_language")
        public String targetLanguage;
        @JsonProperty("error")
        public String error;
    }
}
