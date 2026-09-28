package com.chris64233.constructioninspection.service;

import com.chris64233.constructioninspection.domain.Conclusion;
import com.chris64233.constructioninspection.domain.ConstructionStage;
import com.chris64233.constructioninspection.domain.InspectionItemDefinition;
import com.chris64233.constructioninspection.domain.InspectionRecord;
import com.chris64233.constructioninspection.domain.Permit;
import com.chris64233.constructioninspection.domain.PlanVersion;
import com.chris64233.constructioninspection.domain.Rectification;
import com.chris64233.constructioninspection.domain.RectificationStatus;
import com.chris64233.constructioninspection.domain.StageAcceptance;
import com.chris64233.constructioninspection.domain.StageStatus;
import com.chris64233.constructioninspection.domain.VersionReason;
import com.chris64233.constructioninspection.domain.WorkVersion;
import com.chris64233.constructioninspection.repository.ConstructionStageRepository;
import com.chris64233.constructioninspection.repository.InspectionItemDefinitionRepository;
import com.chris64233.constructioninspection.repository.InspectionRecordRepository;
import com.chris64233.constructioninspection.repository.PermitRepository;
import com.chris64233.constructioninspection.repository.PlanVersionRepository;
import com.chris64233.constructioninspection.repository.RectificationRepository;
import com.chris64233.constructioninspection.repository.WorkVersionRepository;
import com.chris64233.constructioninspection.support.BusinessException;
import com.chris64233.constructioninspection.support.NotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class InspectionService {

    private final PermitRepository permitRepository;
    private final ConstructionStageRepository stageRepository;
    private final InspectionItemDefinitionRepository itemDefinitionRepository;
    private final WorkVersionRepository versionRepository;
    private final InspectionRecordRepository recordRepository;
    private final RectificationRepository rectificationRepository;
    private final PlanVersionRepository planVersionRepository;
    private final com.chris64233.constructioninspection.repository.StageAcceptanceRepository acceptanceRepository;

    public InspectionService(PermitRepository permitRepository,
                             ConstructionStageRepository stageRepository,
                             InspectionItemDefinitionRepository itemDefinitionRepository,
                             WorkVersionRepository versionRepository,
                             InspectionRecordRepository recordRepository,
                             RectificationRepository rectificationRepository,
                             PlanVersionRepository planVersionRepository,
                             com.chris64233.constructioninspection.repository.StageAcceptanceRepository acceptanceRepository) {
        this.permitRepository = permitRepository;
        this.stageRepository = stageRepository;
        this.itemDefinitionRepository = itemDefinitionRepository;
        this.versionRepository = versionRepository;
        this.recordRepository = recordRepository;
        this.rectificationRepository = rectificationRepository;
        this.planVersionRepository = planVersionRepository;
        this.acceptanceRepository = acceptanceRepository;
    }

    /**
     * 提交检查结论。
     * <ul>
     *   <li>submissionNo 为幂等提交号：重复提交返回首次记录，不产生新数据；</li>
     *   <li>仅当阶段处于 ACTIVE（前置阶段均已验收、且未被方案变更挂起）时可提交；</li>
     *   <li>结论落在阶段当前（最新）工程版本上，同一检查项同一版本只允许一条生效结论；</li>
     *   <li>expectedWorkVersionId 非空时必须等于当前工程版本，方案变更产生新版本后旧版本提交被拒绝；</li>
     *   <li>结论不通过时自动生成整改项。</li>
     * </ul>
     */
    @Transactional
    public InspectionRecord submitInspection(String submissionNo, Long itemDefinitionId, Conclusion conclusion,
                                             String inspector, String evidence, Long expectedWorkVersionId) {
        var existing = recordRepository.findBySubmissionNo(submissionNo);
        if (existing.isPresent()) {
            return existing.get();
        }
        InspectionItemDefinition itemDefinition = itemDefinitionRepository.findById(itemDefinitionId)
                .orElseThrow(() -> new NotFoundException("检查项定义不存在: " + itemDefinitionId));
        // 与整改关闭、阶段验收、方案变更批准共用阶段行锁，保证读到的是当前方案与当前版本
        ConstructionStage stage = stageRepository.findByIdForUpdate(itemDefinition.getStage().getId())
                .orElseThrow(() -> new NotFoundException("施工阶段不存在"));
        if (stage.getStatus() == StageStatus.SUSPENDED) {
            throw new BusinessException("阶段[" + stage.getName()
                    + "]已被方案变更挂起，须按新方案重新检查并待前置阶段验收后恢复");
        }
        if (stage.getStatus() != StageStatus.ACTIVE) {
            throw new BusinessException("前置阶段未完成，阶段[" + stage.getName() + "]当前不可申请检查");
        }
        WorkVersion version = versionRepository.findTopByStageIdOrderByVersionNumberDesc(stage.getId())
                .orElseThrow(() -> new BusinessException("阶段缺少工程版本"));
        if (expectedWorkVersionId != null && !expectedWorkVersionId.equals(version.getId())) {
            String expectedNumber = versionRepository.findById(expectedWorkVersionId)
                    .map(v -> "v" + v.getVersionNumber())
                    .orElse("不存在的版本");
            throw new BusinessException("提交基于的工程版本 " + expectedNumber
                    + " 已过期，当前为 v" + version.getVersionNumber() + "（方案已变更），请按新版本重新提交");
        }
        if (recordRepository.existsByVersionIdAndItemDefinitionId(version.getId(), itemDefinitionId)) {
            throw new BusinessException("该检查项在当前版本 v" + version.getVersionNumber() + " 已存在生效结论");
        }
        InspectionRecord record = new InspectionRecord(
                version, itemDefinition, submissionNo, conclusion, inspector, evidence);
        try {
            record = recordRepository.saveAndFlush(record);
        } catch (DataIntegrityViolationException e) {
            // 唯一约束兜底：并发下同提交号或同(版本,检查项)冲突，重试即可命中幂等/冲突前置校验
            throw new BusinessException("提交冲突：提交号重复或该检查项当前版本已有生效结论，请重试");
        }
        if (conclusion == Conclusion.FAIL) {
            rectificationRepository.save(new Rectification(stage, record));
        }
        return record;
    }

    /** 不携带版本保护参数的便捷入口（不校验调用方持有的工程版本） */
    @Transactional
    public InspectionRecord submitInspection(String submissionNo, Long itemDefinitionId, Conclusion conclusion,
                                             String inspector, String evidence) {
        return submitInspection(submissionNo, itemDefinitionId, conclusion, inspector, evidence, null);
    }

    /**
     * 整改提交：关闭整改项，并为阶段产生属于当前方案版本的新复检版本（整改链的一环）。
     */
    @Transactional
    public Rectification submitRectification(Long rectificationId, String note) {
        Rectification rectification = rectificationRepository.findById(rectificationId)
                .orElseThrow(() -> new NotFoundException("整改项不存在: " + rectificationId));
        ConstructionStage stage = stageRepository.findByIdForUpdate(rectification.getStage().getId())
                .orElseThrow(() -> new NotFoundException("施工阶段不存在"));
        if (rectification.getStatus() == RectificationStatus.CANCELLED) {
            throw new BusinessException("整改项所属方案已变更，整改项已取消，须按新版本重新检查");
        }
        if (stage.getStatus() != StageStatus.ACTIVE) {
            throw new BusinessException("阶段[" + stage.getName() + "]已验收或不可整改");
        }
        if (rectification.getStatus() == RectificationStatus.CLOSED) {
            throw new BusinessException("整改项已关闭，不可重复提交");
        }
        int planNumber = stage.getPermit().getCurrentPlanVersionNumber();
        int nextNumber = versionRepository.findMaxVersionNumber(stage.getId()) + 1;
        WorkVersion newVersion = versionRepository.saveAndFlush(
                new WorkVersion(stage, nextNumber, planNumber, VersionReason.RECTIFICATION));
        rectification.close(note, newVersion);
        return rectification;
    }

    /**
     * 阶段一次性验收：当前（最新）工程版本上所有检查项均有有效通过结论、且阶段内未关闭整改项为零时才可通过。
     * 验收与检查提交、整改关闭、方案变更批准共用阶段行锁，因此验收结论永远基于当前方案的最新版本，
     * 不会基于已失效结果。验收追加一条不可变的 StageAcceptance，关联方案版本与工程版本（批准依据链）。
     */
    @Transactional
    public ConstructionStage acceptStage(Long stageId) {
        ConstructionStage stage = stageRepository.findByIdForUpdate(stageId)
                .orElseThrow(() -> new NotFoundException("施工阶段不存在: " + stageId));
        if (stage.getStatus() != StageStatus.ACTIVE) {
            throw new BusinessException("阶段[" + stage.getName() + "]不在可验收状态: " + stage.getStatus());
        }
        WorkVersion currentVersion = versionRepository.findTopByStageIdOrderByVersionNumberDesc(stageId)
                .orElseThrow(() -> new BusinessException("阶段缺少工程版本"));
        List<InspectionItemDefinition> items = itemDefinitionRepository.findByStageIdOrderById(stageId);
        // 只取未失效结论：方案变更后旧版本通过记录不再构成验收依据
        Map<Long, InspectionRecord> recordsByItem = recordRepository
                .findByVersionIdAndInvalidatedFalse(currentVersion.getId())
                .stream()
                .collect(Collectors.toMap(r -> r.getItemDefinition().getId(), Function.identity()));
        for (InspectionItemDefinition item : items) {
            InspectionRecord record = recordsByItem.get(item.getId());
            if (record == null) {
                throw new BusinessException("当前版本 v" + currentVersion.getVersionNumber()
                        + " 检查项[" + item.getCode() + "]尚未检查，不能验收");
            }
            if (record.getConclusion() != Conclusion.PASS) {
                throw new BusinessException("当前版本 v" + currentVersion.getVersionNumber()
                        + " 检查项[" + item.getCode() + "]未通过，不能验收");
            }
        }
        long openRectifications = rectificationRepository.countByStageIdAndStatus(stageId, RectificationStatus.OPEN);
        if (openRectifications > 0) {
            throw new BusinessException("存在 " + openRectifications + " 个未关闭的整改项，不能验收");
        }
        Permit permit = stage.getPermit();
        PlanVersion planVersion = planVersionRepository
                .findByPermitIdAndVersionNumber(permit.getId(), permit.getCurrentPlanVersionNumber())
                .orElseThrow(() -> new BusinessException("缺少当前方案版本"));

        stage.setStatus(StageStatus.COMPLETED);
        stage.setAcceptedVersionId(currentVersion.getId());
        acceptanceRepository.save(new StageAcceptance(stage, planVersion, currentVersion));
        activateNextStage(permit, stage);
        return stage;
    }

    /**
     * 激活下一阶段：
     * <ul>
     *   <li>PENDING（从未开工）：按当前方案生成初始工程版本并激活；</li>
     *   <li>SUSPENDED（被方案变更挂起，已有新版本）：直接恢复为 ACTIVE，不重复生成版本；</li>
     *   <li>其他状态不处理。</li>
     * </ul>
     */
    private void activateNextStage(Permit permit, ConstructionStage completedStage) {
        stageRepository.findByPermitIdAndSeq(permit.getId(), completedStage.getSeq() + 1)
                .ifPresent(next -> {
                    if (next.getStatus() == StageStatus.PENDING) {
                        next.setStatus(StageStatus.ACTIVE);
                        versionRepository.save(new WorkVersion(next, 1,
                                permit.getCurrentPlanVersionNumber(), VersionReason.INITIAL));
                    } else if (next.getStatus() == StageStatus.SUSPENDED) {
                        next.setStatus(StageStatus.ACTIVE);
                    }
                });
    }

    @Transactional(readOnly = true)
    public List<WorkVersion> listVersions(Long stageId) {
        return versionRepository.findByStageIdOrderByVersionNumber(stageId);
    }

    @Transactional(readOnly = true)
    public List<InspectionRecord> listRecordsByVersions(List<Long> versionIds) {
        if (versionIds.isEmpty()) {
            return List.of();
        }
        return recordRepository.findByVersionIdIn(versionIds);
    }

    @Transactional(readOnly = true)
    public List<Rectification> listRectificationChain(Long stageId) {
        return rectificationRepository.findByStageIdOrderById(stageId);
    }

    @Transactional(readOnly = true)
    public List<StageAcceptance> listAcceptanceHistory(Long stageId) {
        if (stageRepository.findById(stageId).isEmpty()) {
            throw new NotFoundException("施工阶段不存在: " + stageId);
        }
        return acceptanceRepository.findByStageIdOrderByIdAsc(stageId);
    }
}
