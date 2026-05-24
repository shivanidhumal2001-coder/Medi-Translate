package com.meditranslate.repository;

import com.meditranslate.entity.ChatMessage;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {
    List<ChatMessage> findByReportIdOrderByCreatedAtAsc(Long reportId);
}
