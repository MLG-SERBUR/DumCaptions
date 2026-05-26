package com.dumcaptions.translate;

public class LanguageDetector {

    public static boolean isArabicOrKorean(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }

        int arKorCount = 0;
        int letterCount = 0;

        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            
            if (Character.isLetter(codePoint)) {
                letterCount++;
                
                if (isArabic(codePoint) || isHangul(codePoint)) {
                    arKorCount++;
                }
            }
            
            i += Character.charCount(codePoint);
        }

        if (letterCount == 0) {
            return false;
        }

        return (double) arKorCount / letterCount > 0.4;
    }

    public static String detectLanguage(String text) {
        if (text == null || text.isEmpty()) {
            return "unknown";
        }

        int arCount = 0;
        int korCount = 0;
        int letterCount = 0;

        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            
            if (Character.isLetter(codePoint)) {
                letterCount++;
                
                if (isArabic(codePoint)) {
                    arCount++;
                } else if (isHangul(codePoint)) {
                    korCount++;
                }
            }
            
            i += Character.charCount(codePoint);
        }

        if (letterCount == 0) {
            return "unknown";
        }

        if ((double) arCount / letterCount > 0.4) {
            return "ar";
        }
        
        if ((double) korCount / letterCount > 0.4) {
            return "ko";
        }

        return "unknown";
    }

    private static boolean isArabic(int codePoint) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(codePoint);
        return block == Character.UnicodeBlock.ARABIC ||
               block == Character.UnicodeBlock.ARABIC_SUPPLEMENT ||
               block == Character.UnicodeBlock.ARABIC_EXTENDED_A ||
               block == Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_A ||
               block == Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_B;
    }

    private static boolean isHangul(int codePoint) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(codePoint);
        return block == Character.UnicodeBlock.HANGUL_SYLLABLES ||
               block == Character.UnicodeBlock.HANGUL_JAMO ||
               block == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO;
    }
}
