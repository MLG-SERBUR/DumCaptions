package com.dumcaptions.translate.backends;

public interface Translator {
    TranslateResponse translate(String text, String sourceLanguage) throws Exception;
    String getDisplayName();
}
