package com.meditranslate.repository;

import com.meditranslate.entity.ReportAnalysis;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReportAnalysisRepository extends JpaRepository<ReportAnalysis, Long> {

    Optional<ReportAnalysis> findDetailedById(Long id);

    List<ReportAnalysis> findByUserIdOrderByCreatedAtDesc(Long userId);

    List<ReportAnalysis> findByGuestSessionIdOrderByCreatedAtDesc(String guestSessionId);
}
