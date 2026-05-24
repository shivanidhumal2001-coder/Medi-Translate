package com.meditranslate.repository;

import com.meditranslate.entity.MedicationReminder;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MedicationReminderRepository extends JpaRepository<MedicationReminder, Long> {
    List<MedicationReminder> findByReportId(Long reportId);
}
