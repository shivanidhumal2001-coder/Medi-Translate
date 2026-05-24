package com.meditranslate.repository;

import com.meditranslate.entity.ReportFinding;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReportFindingRepository extends JpaRepository<ReportFinding, Long> {
    List<ReportFinding> findByReportId(Long reportId);
}
