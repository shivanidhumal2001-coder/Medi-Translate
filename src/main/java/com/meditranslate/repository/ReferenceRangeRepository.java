package com.meditranslate.repository;

import com.meditranslate.entity.ReferenceRange;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReferenceRangeRepository extends JpaRepository<ReferenceRange, Long> {
    Optional<ReferenceRange> findFirstByAnalyteNameIgnoreCase(String analyteName);
    List<ReferenceRange> findByAnalyteNameContainingIgnoreCase(String analyteName);
}
