package com.dumcaptions.translate.backends;

public class TranslateResponse {
    private String translatedText;
    private String sourceLanguage;
    private String targetLanguage;

    public TranslateResponse(String translatedText, String sourceLanguage, String targetLanguage) {
        this.translatedText = translatedText;
        this.sourceLanguage = sourceLanguage;
        this.targetLanguage = targetLanguage;
    }

    public String getTranslatedText() { return translatedText; }
    public String getSourceLanguage() { return sourceLanguage; }
    public String getTargetLanguage() { return targetLanguage; }
}
