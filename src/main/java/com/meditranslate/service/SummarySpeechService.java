package com.meditranslate.service;

public interface SummarySpeechService {

    boolean isAvailable();

    byte[] synthesizeSummaryAudio(String text, String languageCode, String requestedVoice);
}
