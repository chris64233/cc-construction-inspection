package com.chris64233.constructioninspection.repository;

import com.chris64233.constructioninspection.domain.InspectionRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface InspectionRecordRepository extends JpaRepository<InspectionRecord, Long> {

    Optional<InspectionRecord> findBySubmissionNo(String submissionNo);

    boolean existsByVersionIdAndItemDefinitionId(Long versionId, Long itemDefinitionId);

    List<InspectionRecord> findByVersionId(Long versionId);

    List<InspectionRecord> findByVersionIdIn(Collection<Long> versionIds);
}
