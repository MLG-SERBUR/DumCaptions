package com.dumcaptions.translate.backends;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class MyMemory implements Translator {
    private final String email;
    private final OkHttpClient client;
    private final ObjectMapper mapper;

    public MyMemory(String email) {
        this.email = email;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build();
        this.mapper = new ObjectMapper();
    }

    @Override
    public String getDisplayName() {
        return "MyMemory";
    }

    @Override
    public TranslateResponse translate(String text, String sourceLanguage) throws Exception {
        if (sourceLanguage == null || sourceLanguage.isEmpty() || sourceLanguage.equals("unknown")) {
            sourceLanguage = "auto";
        }

        HttpUrl.Builder urlBuilder = HttpUrl.parse("https://api.mymemory.translated.net/get").newBuilder();
        urlBuilder.addQueryParameter("q", text);
        urlBuilder.addQueryParameter("langpair", sourceLanguage + "|en");
        if (email != null && !email.isEmpty()) {
            urlBuilder.addQueryParameter("de", email);
        }

        Request request = new Request.Builder()
                .url(urlBuilder.build())
                .get()
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("MyMemory returned status: " + response.code() + ", body: " + response.body().string());
            }

            ApiResponse apiResponse = mapper.readValue(response.body().string(), ApiResponse.class);
            if (apiResponse.responseStatus != 200) {
                throw new Exception("MyMemory error status: " + apiResponse.responseStatus);
            }

            String sourceLang = "unknown";
            if (apiResponse.matches != null && !apiResponse.matches.isEmpty()) {
                sourceLang = apiResponse.matches.get(0).sourceLanguage;
                if (sourceLang != null && sourceLang.length() > 2) {
                    sourceLang = sourceLang.substring(0, 2);
                }
            }

            String translated = apiResponse.responseData != null ? apiResponse.responseData.translatedText : null;
            return new TranslateResponse(translated, sourceLang, "en");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class ApiResponse {
        @JsonProperty("responseData")
        public ResponseData responseData;
        @JsonProperty("responseStatus")
        public int responseStatus;
        @JsonProperty("matches")
        public List<Match> matches;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class ResponseData {
        @JsonProperty("translatedText")
        public String translatedText;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class Match {
        @JsonProperty("source")
        public String sourceLanguage;
    }
}
