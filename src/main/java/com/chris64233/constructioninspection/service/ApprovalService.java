package com.chris64233.constructioninspection.service;

import com.chris64233.constructioninspection.domain.ConstructionStage;
import com.chris64233.constructioninspection.domain.FinalApproval;
import com.chris64233.constructioninspection.domain.InspectionRecord;
import com.chris64233.constructioninspection.domain.Permit;
import com.chris64233.constructioninspection.domain.PermitStatus;
import com.chris64233.constructioninspection.domain.StageAcceptance;
import com.chris64233.constructioninspection.domain.StageStatus;
import com.chris64233.constructioninspection.domain.StopWorkOrder;
import com.chris64233.constructioninspection.domain.StopWorkOrderStatus;
import com.chris64233.constructioninspection.domain.WorkVersion;
import com.chris64233.constructioninspection.repository.ConstructionStageRepository;
import com.chris64233.constructioninspection.repository.FinalApprovalRepository;
import com.chris64233.constructioninspection.repository.InspectionRecordRepository;
import com.chris64233.constructioninspection.repository.PermitRepository;
import com.chris64233.constructioninspection.repository.StageAcceptanceRepository;
import com.chris64233.constructioninspection.repository.StopWorkOrderRepository;
import com.chris64233.constructioninspection.repository.WorkVersionRepository;
import com.chris64233.constructioninspection.support.BusinessException;
import com.chris64233.constructioninspection.support.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
public class ApprovalService {

    private final PermitRepository permitRepository;
    private final ConstructionStageRepository stageRepository;
    private final WorkVersionRepository versionRepository;
    private final StopWorkOrderRepository stopWorkOrderRepository;
    private final FinalApprovalRepository finalApprovalRepository;
    private final StageAcceptanceRepository acceptanceRepository;
    private final InspectionRecordRepository recordRepository;

    public ApprovalService(PermitRepository permitRepository,
                           ConstructionStageRepository stageRepository,
                           WorkVersionRepository versionRepository,
                           StopWorkOrderRepository stopWorkOrderRepository,
                           FinalApprovalRepository finalApprovalRepository,
                           StageAcceptanceRepository acceptanceRepository,
                           InspectionRecordRepository recordRepository) {
        this.permitRepository = permitRepository;
        this.stageRepository = stageRepository;
        this.versionRepository = versionRepository;
        this.stopWorkOrderRepository = stopWorkOrderRepository;
        this.finalApprovalRepository = finalApprovalRepository;
        this.acceptanceRepository = acceptanceRepository;
        this.recordRepository = recordRepository;
    }

    /**
     * 最终使用批准。
     * <ul>
     *   <li>取许可行级悲观锁：与方案变更批准、停工令签发串行，并发时只形成一种结果；</li>
     *   <li>expectedPlanVersionNumber 非空且不等于当前方案版本时拒绝过期操作
     *       （方案在批准期间被变更，受影响阶段尚未按新版本复检）；</li>
     *   <li>要求全部阶段 COMPLETED，且每个阶段验收依据的工程版本上所有检查结果仍有效；</li>
     *   <li>不存在活动停工令。批准记录一经形成不可修改。</li>
     * </ul>
     */
    @Transactional
    public FinalApproval approve(Long permitId, Integer expectedPlanVersionNumber) {
        Permit permit = permitRepository.findByIdForUpdate(permitId)
                .orElseThrow(() -> new NotFoundException("许可不存在: " + permitId));
        if (finalApprovalRepository.findByPermitId(permitId).isPresent()) {
            throw new BusinessException("许可已最终批准，批准记录不可修改");
        }
        if (expectedPlanVersionNumber != null
                && expectedPlanVersionNumber != permit.getCurrentPlanVersionNumber()) {
            throw new BusinessException("最终批准基于方案 v" + expectedPlanVersionNumber
                    + "，当前方案已演进至 v" + permit.getCurrentPlanVersionNumber()
                    + "，受影响阶段须重新检查后再批准");
        }
        long unfinished = stageRepository.countByPermitIdAndStatusNot(permitId, StageStatus.COMPLETED);
        if (unfinished > 0) {
            throw new BusinessException("存在 " + unfinished + " 个未完成阶段（含被方案变更打回待复检阶段），不能最终批准");
        }
        long activeStopOrders = stopWorkOrderRepository.countByPermitIdAndStatus(permitId, StopWorkOrderStatus.ACTIVE);
        if (activeStopOrders > 0) {
            throw new BusinessException("存在活动停工令，不能最终批准");
        }
        List<StageAcceptance> acceptances = verifyAndCollectAcceptances(permitId);
        String basis = buildBasis(permit, acceptances);
        permit.setStatus(PermitStatus.APPROVED);
        return finalApprovalRepository.save(
                new FinalApproval(permit, permit.getCurrentPlanVersionNumber(), basis));
    }

    /** 无版本号入参的便捷入口（内部/测试使用） */
    @Transactional
    public FinalApproval approve(Long permitId) {
        return approve(permitId, null);
    }

    /**
     * 校验每个已完成阶段的最新验收依据：验收工程版本存在、且其上检查结果全部仍有效（未被方案变更失效）。
     * 收集各阶段最新验收记录，供批准依据快照使用。
     */
    private List<StageAcceptance> verifyAndCollectAcceptances(Long permitId) {
        List<StageAcceptance> result = new ArrayList<>();
        for (ConstructionStage stage : stageRepository.findByPermitIdOrderBySeq(permitId)) {
            List<StageAcceptance> history = acceptanceRepository.findByStageIdOrderByIdAsc(stage.getId());
            if (history.isEmpty()) {
                throw new BusinessException("阶段[" + stage.getName() + "]缺少验收依据，不能最终批准");
            }
            StageAcceptance latest = history.get(history.size() - 1);
            WorkVersion acceptedVersion = latest.getAcceptedVersion();
            if (!acceptedVersion.getId().equals(stage.getAcceptedVersionId())) {
                throw new BusinessException("阶段[" + stage.getName() + "]验收依据与当前状态不一致，不能最终批准");
            }
            List<InspectionRecord> records = recordRepository.findByVersionId(acceptedVersion.getId());
            if (records.isEmpty()) {
                throw new BusinessException("阶段[" + stage.getName() + "]验收版本缺少检查结果，不能最终批准");
            }
            for (InspectionRecord record : records) {
                if (record.isInvalidated()) {
                    throw new BusinessException("阶段[" + stage.getName()
                            + "]验收依据含已被方案变更失效的检查结果，须重新检查后再批准");
                }
            }
            result.add(latest);
        }
        return result;
    }

    /**
     * 签发停工令。与最终批准、方案变更批准互斥：许可已批准则不可再签发。
     */
    @Transactional
    public StopWorkOrder issueStopWorkOrder(Long permitId, String reason) {
        Permit permit = permitRepository.findByIdForUpdate(permitId)
                .orElseThrow(() -> new NotFoundException("许可不存在: " + permitId));
        if (finalApprovalRepository.findByPermitId(permitId).isPresent()) {
            throw new BusinessException("许可已最终批准，不可再签发停工令");
        }
        return stopWorkOrderRepository.save(new StopWorkOrder(permit, reason));
    }

    @Transactional
    public StopWorkOrder liftStopWorkOrder(Long orderId) {
        StopWorkOrder order = stopWorkOrderRepository.findById(orderId)
                .orElseThrow(() -> new NotFoundException("停工令不存在: " + orderId));
        if (order.getStatus() != StopWorkOrderStatus.ACTIVE) {
            throw new BusinessException("停工令已解除");
        }
        order.lift();
        return order;
    }

    /** 最终批准依据查询：批准记录 + 各阶段最新验收（方案版本/工程版本）+ 停工令历史 */
    @Transactional(readOnly = true)
    public FinalApprovalBasis getApprovalBasis(Long permitId) {
        FinalApproval approval = finalApprovalRepository.findByPermitId(permitId)
                .orElseThrow(() -> new NotFoundException("许可尚未最终批准: " + permitId));
        List<StageAcceptanceView> stages = new ArrayList<>();
        for (ConstructionStage stage : stageRepository.findByPermitIdOrderBySeq(permitId)) {
            List<StageAcceptance> history = acceptanceRepository.findByStageIdOrderByIdAsc(stage.getId());
            StageAcceptance latest = history.isEmpty() ? null : history.get(history.size() - 1);
            stages.add(new StageAcceptanceView(
                    stage.getSeq(), stage.getName(), stage.getStatus().name(),
                    latest == null ? null : latest.getPlanVersion().getVersionNumber(),
                    latest == null ? null : latest.getAcceptedVersion().getVersionNumber(),
                    history.size()));
        }
        List<StopWorkOrder> orders = stopWorkOrderRepository.findByPermitIdOrderById(permitId);
        return new FinalApprovalBasis(approval, stages, orders);
    }

    private String buildBasis(Permit permit, List<StageAcceptance> acceptances) {
        StringBuilder basis = new StringBuilder();
        basis.append("最终批准基于当前方案 v").append(permit.getCurrentPlanVersionNumber())
                .append("，全部 ").append(acceptances.size()).append(" 个施工阶段已按当前有效结果验收完成：");
        for (StageAcceptance acceptance : acceptances) {
            ConstructionStage stage = acceptance.getStage();
            basis.append(stage.getName())
                    .append("(方案 v").append(acceptance.getPlanVersion().getVersionNumber())
                    .append("、工程版本 v").append(acceptance.getAcceptedVersion().getVersionNumber())
                    .append(")；");
        }
        basis.append("批准时点无活动停工令。");
        return basis.toString();
    }

    public record StageAcceptanceView(int seq, String name, String status,
                                      Integer planVersionNumber, Integer acceptedVersionNumber,
                                      int acceptanceCount) {
    }

    public record FinalApprovalBasis(FinalApproval approval, List<StageAcceptanceView> stages,
                                     List<StopWorkOrder> stopWorkOrders) {
    }
}
