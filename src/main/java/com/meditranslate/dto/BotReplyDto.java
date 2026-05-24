package com.meditranslate.dto;

public class BotReplyDto {

    private final String answer;
    private final boolean savedToHistory;

    public BotReplyDto(String answer, boolean savedToHistory) {
        this.answer = answer;
        this.savedToHistory = savedToHistory;
    }

    public String getAnswer() {
        return answer;
    }

    public boolean isSavedToHistory() {
        return savedToHistory;
    }
}
