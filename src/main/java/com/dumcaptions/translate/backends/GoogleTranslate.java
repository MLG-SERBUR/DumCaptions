package com.dumcaptions.translate.backends;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

public class GoogleTranslate implements Translator {
    private final OkHttpClient client;
    private final ObjectMapper mapper;

    public GoogleTranslate() {
        this.client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build();
        this.mapper = new ObjectMapper();
    }

    @Override
    public String getDisplayName() {
        return "Google";
    }

    @Override
    public TranslateResponse translate(String text, String sourceLanguage) throws Exception {
        if (sourceLanguage == null || sourceLanguage.isEmpty() || sourceLanguage.equals("unknown")) {
            sourceLanguage = "auto";
        }

        HttpUrl.Builder urlBuilder = HttpUrl.parse("https://translate-pa.googleapis.com/v1/translate").newBuilder();
        urlBuilder.addQueryParameter("params.client", "gtx");
        urlBuilder.addQueryParameter("dataTypes", "TRANSLATION");
        urlBuilder.addQueryParameter("key", "AIzaSyDLEeFI5OtFBwYBIoK_jj5m32rZK5CkCXA");
        urlBuilder.addQueryParameter("query.sourceLanguage", sourceLanguage);
        urlBuilder.addQueryParameter("query.targetLanguage", "en");
        urlBuilder.addQueryParameter("query.text", text);

        Request request = new Request.Builder()
                .url(urlBuilder.build())
                .get()
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Google translate returned status: " + response.code() + ", body: " + response.body().string());
            }

            ApiResponse apiResponse = mapper.readValue(response.body().string(), ApiResponse.class);
            return new TranslateResponse(apiResponse.translation, apiResponse.sourceLanguage, "en");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class ApiResponse {
        @JsonProperty("sourceLanguage")
        public String sourceLanguage;
        @JsonProperty("translation")
        public String translation;
    }
}
