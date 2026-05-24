package com.meditranslate.service;

import com.meditranslate.dto.BotReplyDto;
import com.meditranslate.entity.ReportAnalysis;
import com.meditranslate.entity.UserAccount;

public interface BotService {
    BotReplyDto answerQuestion(ReportAnalysis report, String question, UserAccount user);
}
