package com.chris64233.constructioninspection.service;

import com.chris64233.constructioninspection.domain.Amendment;
import com.chris64233.constructioninspection.domain.AmendmentStatus;
import com.chris64233.constructioninspection.domain.ConstructionStage;
import com.chris64233.constructioninspection.domain.Permit;
import com.chris64233.constructioninspection.domain.PermitStatus;
import com.chris64233.constructioninspection.domain.PlanVersion;
import com.chris64233.constructioninspection.domain.Rectification;
import com.chris64233.constructioninspection.domain.RectificationStatus;
import com.chris64233.constructioninspection.domain.StageStatus;
import com.chris64233.constructioninspection.domain.VersionReason;
import com.chris64233.constructioninspection.domain.WorkVersion;
import com.chris64233.constructioninspection.repository.AmendmentRepository;
import com.chris64233.constructioninspection.repository.ConstructionStageRepository;
import com.chris64233.constructioninspection.repository.PermitRepository;
import com.chris64233.constructioninspection.repository.PlanVersionRepository;
import com.chris64233.constructioninspection.repository.RectificationRepository;
import com.chris64233.constructioninspection.repository.WorkVersionRepository;
import com.chris64233.constructioninspection.support.BusinessException;
import com.chris64233.constructioninspection.support.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
public class AmendmentService {

    private final AmendmentRepository amendmentRepository;
    private final PermitRepository permitRepository;
    private final ConstructionStageRepository stageRepository;
    private final PlanVersionRepository planVersionRepository;
    private final WorkVersionRepository versionRepository;
    private final RectificationRepository rectificationRepository;

    public AmendmentService(AmendmentRepository amendmentRepository,
                            PermitRepository permitRepository,
                            ConstructionStageRepository stageRepository,
                            PlanVersionRepository planVersionRepository,
                            WorkVersionRepository versionRepository,
                            RectificationRepository rectificationRepository) {
        this.amendmentRepository = amendmentRepository;
        this.permitRepository = permitRepository;
        this.stageRepository = stageRepository;
        this.planVersionRepository = planVersionRepository;
        this.versionRepository = versionRepository;
        this.rectificationRepository = rectificationRepository;
    }

    /**
     * 登记方案变更：标明受影响的施工阶段。变更批准前不产生任何版本与状态变化。
     */
    @Transactional
    public Amendment createAmendment(Long permitId, String description, List<Long> affectedStageIds) {
        Permit permit = permitRepository.findById(permitId)
                .orElseThrow(() -> new NotFoundException("许可不存在: " + permitId));
        if (permit.getStatus() == PermitStatus.APPROVED) {
            throw new BusinessException("许可已最终批准，不可再发起方案变更");
        }
        if (affectedStageIds == null || affectedStageIds.isEmpty()) {
            throw new BusinessException("变更必须标明至少一个受影响阶段");
        }
        List<Long> stageIds = affectedStageIds.stream().distinct().toList();
        for (Long stageId : stageIds) {
            ConstructionStage stage = stageRepository.findById(stageId)
                    .orElseThrow(() -> new NotFoundException("施工阶段不存在: " + stageId));
            if (!stage.getPermit().getId().equals(permitId)) {
                throw new BusinessException("阶段[" + stage.getName() + "]不属于许可 " + permitId);
            }
        }
        return amendmentRepository.save(new Amendment(permit, description, stageIds));
    }

    /**
     * 批准变更：生成新方案版本，仅使受影响阶段的检查结果失效。
     * <ul>
     *   <li>与最终批准、停工令签发共用许可行级悲观锁，并发时只形成一种结果；</li>
     *   <li>expectedPlanVersion 与当前方案版本不一致时拒绝过期批准；</li>
     *   <li>受影响阶段产生 AMENDMENT 原因的新工程版本并回到 ACTIVE，须按新方案重新检查；
     *       未关闭的整改项随旧方案作废；未受影响阶段的状态与有效结果完整保留；</li>
     *   <li>方案版本、失效检查、复检版本在同一事务内更新，要么全部生效要么全部回滚。</li>
     * </ul>
     */
    @Transactional
    public Amendment approveAmendment(Long amendmentId, int expectedPlanVersion) {
        Amendment amendment = amendmentRepository.findById(amendmentId)
                .orElseThrow(() -> new NotFoundException("变更单不存在: " + amendmentId));
        Permit permit = permitRepository.findByIdForUpdate(amendment.getPermit().getId())
                .orElseThrow(() -> new NotFoundException("许可不存在"));
        if (permit.getStatus() == PermitStatus.APPROVED) {
            throw new BusinessException("许可已最终批准，不可再批准变更");
        }
        if (amendment.getStatus() != AmendmentStatus.PENDING) {
            throw new BusinessException("变更单已批准，不可重复批准");
        }
        int currentPlan = permit.getCurrentPlanVersionNumber();
        if (currentPlan != expectedPlanVersion) {
            throw new BusinessException("方案已变更至 v" + currentPlan + "，基于方案 v" + expectedPlanVersion
                    + " 的变更批准已过期");
        }
        PlanVersion newPlan = planVersionRepository.save(
                new PlanVersion(permit, currentPlan + 1, amendment));
        permit.setCurrentPlanVersionNumber(currentPlan + 1);
        // 按阶段顺序加锁，与检查提交/整改/验收的阶段锁顺序一致，避免死锁
        List<ConstructionStage> affected = amendment.getAffectedStageIds().stream()
                .map(stageId -> stageRepository.findByIdForUpdate(stageId)
                        .orElseThrow(() -> new NotFoundException("施工阶段不存在: " + stageId)))
                .sorted(Comparator.comparingInt(ConstructionStage::getSeq))
                .toList();
        for (ConstructionStage stage : affected) {
            int maxVersion = versionRepository.findMaxVersionNumber(stage.getId());
            if (maxVersion == 0) {
                // 阶段尚未激活，没有任何检查结果需要失效
                continue;
            }
            WorkVersion amendmentVersion = versionRepository.save(
                    new WorkVersion(stage, maxVersion + 1, VersionReason.AMENDMENT, newPlan));
            // 旧版本上的检查记录保留为历史，但不再构成验收依据；阶段须按新方案重新检查
            stage.setStatus(StageStatus.ACTIVE);
            stage.setAcceptedVersionId(null);
            for (Rectification open : rectificationRepository.findByStageIdAndStatus(
                    stage.getId(), RectificationStatus.OPEN)) {
                open.close("方案 v" + newPlan.getVersionNumber() + " 变更批准，原整改项作废", amendmentVersion);
            }
        }
        amendment.approve();
        return amendment;
    }

    @Transactional(readOnly = true)
    public List<Amendment> listAmendments(Long permitId) {
        return amendmentRepository.findByPermitIdOrderById(permitId);
    }

    @Transactional(readOnly = true)
    public List<PlanVersion> listPlanVersions(Long permitId) {
        return planVersionRepository.findByPermitIdOrderByVersionNumber(permitId);
    }

    /** 变更单批准时生成的方案版本（未批准时为空） */
    @Transactional(readOnly = true)
    public Optional<PlanVersion> planVersionOf(Long amendmentId) {
        return planVersionRepository.findByAmendmentId(amendmentId);
    }
}
