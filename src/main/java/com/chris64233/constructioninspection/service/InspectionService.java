package com.chris64233.constructioninspection.service;

import com.chris64233.constructioninspection.domain.Conclusion;
import com.chris64233.constructioninspection.domain.InspectionRecord;
import com.chris64233.constructioninspection.domain.Rectification;
import com.chris64233.constructioninspection.domain.RectificationStatus;
import com.chris64233.constructioninspection.domain.Stage;
import com.chris64233.constructioninspection.domain.StageItem;
import com.chris64233.constructioninspection.domain.StageStatus;
import com.chris64233.constructioninspection.repo.InspectionRecordRepository;
import com.chris64233.constructioninspection.repo.RectificationRepository;
import com.chris64233.constructioninspection.repo.StageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 检查、整改与阶段验收。所有写操作先对阶段行加悲观写锁，
 * 因此同一阶段内的并发复检、整改提交与验收被串行化，
 * 验收永远基于提交时的最新版本状态，不会读到过期结果。
 */
@Service
public class InspectionService {

    private final StageRepository stageRepository;
    private final InspectionRecordRepository inspectionRepository;
    private final RectificationRepository rectificationRepository;

    public InspectionService(StageRepository stageRepository,
                             InspectionRecordRepository inspectionRepository,
                             RectificationRepository rectificationRepository) {
        this.stageRepository = stageRepository;
        this.inspectionRepository = inspectionRepository;
        this.rectificationRepository = rectificationRepository;
    }

    public record InspectionResult(InspectionRecord record, Rectification rectification, boolean idempotentReplay) {
    }

    @Transactional
    public InspectionResult submitInspection(Long permitId, int sequence, String itemCode,
                                             Conclusion conclusion, String inspector,
                                             String evidence, String submissionNo) {
        Stage stage = lockStage(permitId, sequence);

        // 提交号幂等：同一提交号重复提交直接返回首次结果
        Optional<InspectionRecord> replay =
                inspectionRepository.findByStageIdAndSubmissionNo(stage.getId(), submissionNo);
        if (replay.isPresent()) {
            return new InspectionResult(replay.get(), null, true);
        }

        if (stage.getStatus() == StageStatus.ACCEPTED) {
            throw BusinessException.conflict("STAGE_ACCEPTED", "阶段已验收，不能再提交检查");
        }
        ensurePreviousStagesAccepted(permitId, sequence);
        if (!stage.hasItem(itemCode)) {
            throw BusinessException.unprocessable("ITEM_NOT_DEFINED",
                    "检查项不属于该阶段: " + itemCode);
        }

        int version = stage.getCurrentVersion();
        // 一个检查项同一版本只能有一个生效结论（并发复检由阶段锁串行化，后到者在此被拒绝）
        if (inspectionRepository.findByStageIdAndItemCodeAndVersion(stage.getId(), itemCode, version).isPresent()) {
            throw BusinessException.conflict("CONCLUSION_EXISTS",
                    "检查项 " + itemCode + " 在版本 " + version + " 已存在生效结论");
        }

        InspectionRecord record = inspectionRepository.save(new InspectionRecord(
                stage, itemCode, version, conclusion, inspector, evidence, submissionNo, Instant.now()));

        Rectification rectification = null;
        if (conclusion == Conclusion.FAIL) {
            // 不通过必须生成整改项
            rectification = rectificationRepository.save(new Rectification(
                    stage, itemCode, version, record,
                    "检查项 " + itemCode + " 在版本 " + version + " 检查不通过，需整改",
                    Instant.now()));
        } else {
            // 复检通过：关闭该检查项已提交的整改项
            for (Rectification r : rectificationRepository.findByStageIdAndItemCodeAndStatus(
                    stage.getId(), itemCode, RectificationStatus.SUBMITTED)) {
                r.close(version, Instant.now());
            }
        }
        return new InspectionResult(record, rectification, false);
    }

    @Transactional
    public Rectification submitRectification(Long permitId, int sequence, Long rectificationId,
                                             String submissionNo) {
        Stage stage = lockStage(permitId, sequence);
        Rectification rectification = rectificationRepository.findByIdAndStageId(rectificationId, stage.getId())
                .orElseThrow(() -> BusinessException.notFound("整改项不存在: " + rectificationId));

        // 整改提交幂等：同一提交号重复提交返回当前状态
        if (rectification.getSubmissionNo() != null) {
            if (rectification.getSubmissionNo().equals(submissionNo)) {
                return rectification;
            }
            throw BusinessException.conflict("RECTIFICATION_ALREADY_SUBMITTED", "整改项已提交，不能重复提交");
        }
        if (rectification.getStatus() != RectificationStatus.OPEN) {
            throw BusinessException.conflict("RECTIFICATION_NOT_OPEN", "整改项当前状态不允许提交: " + rectification.getStatus());
        }

        rectification.markSubmitted(submissionNo, Instant.now());
        // 整改提交产生新的复检版本
        stage.bumpVersion();
        return rectification;
    }

    /**
     * 阶段一次性验收：当前版本的所有检查项均通过，且不存在未关闭的整改项。
     * 验收在阶段锁内完成，读取的是最新版本状态。
     */
    @Transactional
    public Stage acceptStage(Long permitId, int sequence) {
        Stage stage = lockStage(permitId, sequence);
        if (stage.getStatus() == StageStatus.ACCEPTED) {
            return stage;
        }
        ensurePreviousStagesAccepted(permitId, sequence);

        int version = stage.getCurrentVersion();
        long openRectifications = rectificationRepository.countByStageIdAndStatusIn(stage.getId(),
                List.of(RectificationStatus.OPEN, RectificationStatus.SUBMITTED));
        if (openRectifications > 0) {
            throw BusinessException.unprocessable("RECTIFICATIONS_OPEN",
                    "存在 " + openRectifications + " 个未关闭的整改项，不能验收");
        }
        for (StageItem item : stage.getItems()) {
            if (!inspectionRepository.existsByStageIdAndItemCodeAndVersionAndConclusion(
                    stage.getId(), item.getItemCode(), version, Conclusion.PASS)) {
                throw BusinessException.unprocessable("ITEMS_NOT_PASSED",
                        "检查项 " + item.getItemCode() + " 在当前版本 " + version + " 未通过");
            }
        }
        stage.accept(Instant.now());
        return stage;
    }

    @Transactional(readOnly = true)
    public List<InspectionRecord> listInspections(Long permitId, int sequence, Integer version) {
        Stage stage = getStage(permitId, sequence);
        if (version != null) {
            return inspectionRepository.findByStageIdAndVersionOrderByItemCodeAsc(stage.getId(), version);
        }
        return inspectionRepository.findByStageIdOrderByVersionAscItemCodeAsc(stage.getId());
    }

    @Transactional(readOnly = true)
    public List<Rectification> listRectifications(Long permitId, int sequence) {
        Stage stage = getStage(permitId, sequence);
        return rectificationRepository.findByStageIdOrderByCreatedAtAscIdAsc(stage.getId());
    }

    private Stage lockStage(Long permitId, int sequence) {
        return stageRepository.findByPermitIdAndSequenceForUpdate(permitId, sequence)
                .orElseThrow(() -> BusinessException.notFound("阶段不存在: permit=" + permitId + ", seq=" + sequence));
    }

    private Stage getStage(Long permitId, int sequence) {
        return stageRepository.findByPermitIdOrderBySequenceAsc(permitId).stream()
                .filter(s -> s.getSequence() == sequence)
                .findFirst()
                .orElseThrow(() -> BusinessException.notFound("阶段不存在: permit=" + permitId + ", seq=" + sequence));
    }

    private void ensurePreviousStagesAccepted(Long permitId, int sequence) {
        if (stageRepository.existsByPermitIdAndSequenceLessThanAndStatusNot(
                permitId, sequence, StageStatus.ACCEPTED)) {
            throw BusinessException.unprocessable("PREVIOUS_STAGE_NOT_ACCEPTED",
                    "前置阶段未验收完成，不能申请后续阶段检查/验收");
        }
    }
}
