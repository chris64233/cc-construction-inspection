package com.chris64233.constructioninspection.repo;

import com.chris64233.constructioninspection.domain.Conclusion;
import com.chris64233.constructioninspection.domain.InspectionRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface InspectionRecordRepository extends JpaRepository<InspectionRecord, Long> {

    Optional<InspectionRecord> findByStageIdAndSubmissionNo(Long stageId, String submissionNo);

    Optional<InspectionRecord> findByStageIdAndItemCodeAndVersion(Long stageId, String itemCode, int version);

    boolean existsByStageIdAndItemCodeAndVersionAndConclusion(Long stageId, String itemCode, int version,
                                                              Conclusion conclusion);

    List<InspectionRecord> findByStageIdOrderByVersionAscItemCodeAsc(Long stageId);

    List<InspectionRecord> findByStageIdAndVersionOrderByItemCodeAsc(Long stageId, int version);
}
