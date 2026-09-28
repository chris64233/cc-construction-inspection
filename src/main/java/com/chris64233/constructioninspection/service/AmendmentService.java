package com.chris64233.constructioninspection.service;

import com.chris64233.constructioninspection.domain.Amendment;
import com.chris64233.constructioninspection.domain.AmendmentAffectedStage;
import com.chris64233.constructioninspection.domain.AmendmentStatus;
import com.chris64233.constructioninspection.domain.ConstructionStage;
import com.chris64233.constructioninspection.domain.Permit;
import com.chris64233.constructioninspection.domain.PlanVersion;
import com.chris64233.constructioninspection.domain.PlanVersionReason;
import com.chris64233.constructioninspection.domain.StageStatus;
import com.chris64233.constructioninspection.domain.VersionReason;
import com.chris64233.constructioninspection.domain.WorkVersion;
import com.chris64233.constructioninspection.repository.AmendmentRepository;
import com.chris64233.constructioninspection.repository.ConstructionStageRepository;
import com.chris64233.constructioninspection.repository.FinalApprovalRepository;
import com.chris64233.constructioninspection.repository.InspectionRecordRepository;
import com.chris64233.constructioninspection.repository.PermitRepository;
import com.chris64233.constructioninspection.repository.PlanVersionRepository;
import com.chris64233.constructioninspection.repository.RectificationRepository;
import com.chris64233.constructioninspection.repository.WorkVersionRepository;
import com.chris64233.constructioninspection.support.BusinessException;
import com.chris64233.constructioninspection.support.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 方案变更流程。
 *
 * <p>变更单提交时只记录受影响阶段与其基准方案版本；批准在单个事务内完成：
 * 生成新方案版本 → 仅失效受影响阶段的当前有效检查结果、取消其未关闭整改项 →
 * 为已开工的受影响阶段产生 AMENDMENT 工作版本并重置验收状态。
 *
 * <p>并发：批准先取许可行级悲观锁（与最终批准互斥），再按 seq 升序取各受影响阶段行锁
 * （与检查提交、整改提交、阶段验收互斥），因此失效处理与检查写入不会交错。
 */
@Service
public class AmendmentService {

    private final PermitRepository permitRepository;
    private final ConstructionStageRepository stageRepository;
    private final WorkVersionRepository versionRepository;
    private final InspectionRecordRepository recordRepository;
    private final RectificationRepository rectificationRepository;
    private final PlanVersionRepository planVersionRepository;
    private final AmendmentRepository amendmentRepository;
    private final FinalApprovalRepository finalApprovalRepository;

    public AmendmentService(PermitRepository permitRepository,
                            ConstructionStageRepository stageRepository,
                            WorkVersionRepository versionRepository,
                            InspectionRecordRepository recordRepository,
                            RectificationRepository rectificationRepository,
                            PlanVersionRepository planVersionRepository,
                            AmendmentRepository amendmentRepository,
                            FinalApprovalRepository finalApprovalRepository) {
        this.permitRepository = permitRepository;
        this.stageRepository = stageRepository;
        this.versionRepository = versionRepository;
        this.recordRepository = recordRepository;
        this.rectificationRepository = rectificationRepository;
        this.planVersionRepository = planVersionRepository;
        this.amendmentRepository = amendmentRepository;
        this.finalApprovalRepository = finalApprovalRepository;
    }

    /** 单个受影响阶段在批准后的处理结果，用于明确"失效了什么、需要复检什么" */
    public record AffectedStageEffect(Long stageId, int stageSeq, String stageName,
                                      String statusBefore, String statusAfter,
                                      int invalidatedRecordCount, int cancelledRectificationCount,
                                      Integer newWorkVersionNumber, boolean mustReinspect) {
    }

    public record AmendmentApproval(Amendment amendment, PlanVersion planVersion,
                                    List<AffectedStageEffect> effects) {
    }

    /**
     * 提交方案变更：标明受影响阶段，快照当前方案版本作为基准。
     * 同一许可同时只允许一个待批准变更，保证基准版本不产生歧义。
     */
    @Transactional
    public Amendment propose(Long permitId, String summary, List<Long> affectedStageIds) {
        Permit permit = permitRepository.findByIdForUpdate(permitId)
                .orElseThrow(() -> new NotFoundException("许可不存在: " + permitId));
        ensureNotFinallyApproved(permitId);
        if (summary == null || summary.isBlank()) {
            throw new BusinessException("变更说明不能为空");
        }
        if (affectedStageIds == null || affectedStageIds.isEmpty()) {
            throw new BusinessException("方案变更至少标明一个受影响施工阶段");
        }
        List<Long> distinctStageIds = affectedStageIds.stream().distinct().toList();
        if (distinctStageIds.size() != affectedStageIds.size()) {
            throw new BusinessException("受影响施工阶段不能重复");
        }
        if (amendmentRepository.countByPermitIdAndStatus(permitId, AmendmentStatus.PROPOSED) > 0) {
            throw new BusinessException("该许可已有待批准的方案变更，请先处理后再提交");
        }
        List<ConstructionStage> stages = stageRepository.findByPermitIdOrderBySeq(permitId);
        Set<Long> permitStageIds = new HashSet<>();
        stages.forEach(s -> permitStageIds.add(s.getId()));
        for (Long stageId : distinctStageIds) {
            if (!permitStageIds.contains(stageId)) {
                throw new BusinessException("施工阶段不属于该许可: " + stageId);
            }
        }

        Amendment amendment = new Amendment(permit, summary, permit.getCurrentPlanVersionNumber());
        for (ConstructionStage stage : stages) {
            if (distinctStageIds.contains(stage.getId())) {
                amendment.addAffectedStage(stage);
            }
        }
        return amendmentRepository.save(amendment);
    }

    /**
     * 批准方案变更。
     *
     * @param expectedBasePlanVersionNumber 调用方持有的方案版本号；非空且与基准不一致时拒绝过期操作
     */
    @Transactional
    public AmendmentApproval approve(Long amendmentId, Integer expectedBasePlanVersionNumber) {
        Amendment amendment = amendmentRepository.findById(amendmentId)
                .orElseThrow(() -> new NotFoundException("变更单不存在: " + amendmentId));
        // 先锁许可行：与最终使用批准串行，杜绝"变更批准"与"最终批准"同时生效
        Permit permit = permitRepository.findByIdForUpdate(amendment.getPermit().getId())
                .orElseThrow(() -> new NotFoundException("许可不存在"));
        ensureNotFinallyApproved(permit.getId());
        if (amendment.getStatus() != AmendmentStatus.PROPOSED) {
            throw new BusinessException("变更单已批准，不可重复批准");
        }
        if (expectedBasePlanVersionNumber != null
                && expectedBasePlanVersionNumber != amendment.getBasePlanVersionNumber()) {
            throw new BusinessException("变更基于方案 v" + amendment.getBasePlanVersionNumber()
                    + "，提交方持有的是 v" + expectedBasePlanVersionNumber + "，操作已过期");
        }
        if (permit.getCurrentPlanVersionNumber() != amendment.getBasePlanVersionNumber()) {
            throw new BusinessException("方案已演进至 v" + permit.getCurrentPlanVersionNumber()
                    + "，该变更基于 v" + amendment.getBasePlanVersionNumber() + "，操作已过期");
        }

        int newPlanNumber = permit.getCurrentPlanVersionNumber() + 1;

        Set<Long> affectedStageIds = new HashSet<>();
        for (AmendmentAffectedStage link : amendment.getAffectedStages()) {
            affectedStageIds.add(link.getStage().getId());
        }

        // 许可行锁之后，按 seq 升序一次性锁定全部阶段行：与检查提交、整改提交、阶段验收完全串行。
        List<ConstructionStage> lockedStages = stageRepository.findByPermitIdOrderBySeqForUpdate(permit.getId());

        // 先记录各受影响阶段变更前状态，再执行绕过持久化上下文的批量失效/取消：
        // 即便检查提交在阶段锁临界区内写入了新结果/整改项，批量 UPDATE 也会将其一并命中，不会遗漏。
        java.util.Map<Long, String> statusBeforeById = new java.util.HashMap<>();
        for (ConstructionStage stage : lockedStages) {
            if (affectedStageIds.contains(stage.getId())) {
                statusBeforeById.put(stage.getId(), stage.getStatus().name());
            }
        }
        // 每个受影响阶段的 [失效检查数, 取消整改数]，由批量 UPDATE 返回值直接给出
        java.util.Map<Long, int[]> effectCountsById = new java.util.HashMap<>();
        for (Long stageId : affectedStageIds) {
            int invalidated = recordRepository.invalidateValidRecordsOfStage(stageId, newPlanNumber);
            int cancelled = rectificationRepository.cancelOpenRectificationsOfStage(
                    stageId, newPlanNumber, java.time.Instant.now());
            effectCountsById.put(stageId, new int[]{invalidated, cancelled});
        }
        // 批量更新已清空持久化上下文；在同一事务（许可行锁、阶段行锁仍持有）内重查托管实体，
        // 随后创建方案版本、推进许可、回退阶段，全部随事务原子提交或回滚。
        permit = permitRepository.findById(permit.getId()).orElseThrow();
        amendment = amendmentRepository.findById(amendmentId).orElseThrow();
        amendment.approve(newPlanNumber);
        amendment = amendmentRepository.save(amendment);
        PlanVersion planVersion = planVersionRepository.save(
                new PlanVersion(permit, newPlanNumber, PlanVersionReason.AMENDMENT, amendment));
        permit.setCurrentPlanVersionNumber(newPlanNumber);

        List<ConstructionStage> allStages = stageRepository.findByPermitIdOrderBySeq(permit.getId());
        java.util.List<AffectedStageEffect> effects = new java.util.ArrayList<>();
        for (ConstructionStage stage : allStages) {
            if (!affectedStageIds.contains(stage.getId())) {
                continue;
            }
            String statusBefore = statusBeforeById.get(stage.getId());
            int[] counts = effectCountsById.get(stage.getId());

            Integer newWorkVersionNumber = null;
            boolean mustReinspect;
            WorkVersion latest = versionRepository.findTopByStageIdOrderByVersionNumberDesc(stage.getId())
                    .orElse(null);
            if (latest == null) {
                // 尚未开工的 PENDING 阶段：暂不生成版本，待激活时按当前方案生成初始版本
                mustReinspect = false;
            } else {
                int nextNumber = versionRepository.findMaxVersionNumber(stage.getId()) + 1;
                WorkVersion newVersion = versionRepository.save(
                        new WorkVersion(stage, nextNumber, newPlanNumber, VersionReason.AMENDMENT));
                newWorkVersionNumber = nextNumber;
                stage.setAcceptedVersionId(null);
                mustReinspect = true;
                // 所有前置阶段在本次变更后仍 COMPLETED（未被本次变更波及）时才可立即恢复检查
                stage.setStatus(allPredecessorsRemainCompleted(allStages, stage.getSeq(), affectedStageIds)
                        ? StageStatus.ACTIVE : StageStatus.SUSPENDED);
            }
            effects.add(new AffectedStageEffect(stage.getId(), stage.getSeq(), stage.getName(),
                    statusBefore, stage.getStatus().name(), counts[0], counts[1],
                    newWorkVersionNumber, mustReinspect));
        }
        return new AmendmentApproval(amendment, planVersion, effects);
    }

    private boolean allPredecessorsRemainCompleted(List<ConstructionStage> allStages, int seq,
                                                   Set<Long> affectedStageIds) {
        for (ConstructionStage candidate : allStages) {
            if (candidate.getSeq() < seq
                    && (affectedStageIds.contains(candidate.getId())
                    || candidate.getStatus() != StageStatus.COMPLETED)) {
                return false;
            }
        }
        return true;
    }

    private void ensureNotFinallyApproved(Long permitId) {
        if (finalApprovalRepository.findByPermitId(permitId).isPresent()) {
            throw new BusinessException("许可已最终批准，方案不可再变更");
        }
    }

    @Transactional(readOnly = true)
    public Amendment getAmendment(Long amendmentId) {
        return amendmentRepository.findById(amendmentId)
                .orElseThrow(() -> new NotFoundException("变更单不存在: " + amendmentId));
    }

    @Transactional(readOnly = true)
    public List<Amendment> listAmendments(Long permitId) {
        if (permitRepository.findById(permitId).isEmpty()) {
            throw new NotFoundException("许可不存在: " + permitId);
        }
        return amendmentRepository.findByPermitIdOrderById(permitId);
    }

    @Transactional(readOnly = true)
    public List<PlanVersion> listPlanVersions(Long permitId) {
        if (permitRepository.findById(permitId).isEmpty()) {
            throw new NotFoundException("许可不存在: " + permitId);
        }
        return planVersionRepository.findByPermitIdOrderByVersionNumber(permitId);
    }

    /** 变更单关联的受影响阶段（按 seq 升序） */
    @Transactional(readOnly = true)
    public List<ConstructionStage> listAffectedStages(Long amendmentId) {
        Amendment amendment = getAmendment(amendmentId);
        return amendment.getAffectedStages().stream()
                .map(AmendmentAffectedStage::getStage)
                .toList();
    }
}
