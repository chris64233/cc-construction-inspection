package com.chris64233.constructioninspection.service;

import com.chris64233.constructioninspection.domain.ConstructionStage;
import com.chris64233.constructioninspection.domain.FinalApproval;
import com.chris64233.constructioninspection.domain.Permit;
import com.chris64233.constructioninspection.domain.PermitStatus;
import com.chris64233.constructioninspection.domain.StageStatus;
import com.chris64233.constructioninspection.domain.StopWorkOrder;
import com.chris64233.constructioninspection.domain.StopWorkOrderStatus;
import com.chris64233.constructioninspection.domain.WorkVersion;
import com.chris64233.constructioninspection.repository.ConstructionStageRepository;
import com.chris64233.constructioninspection.repository.FinalApprovalRepository;
import com.chris64233.constructioninspection.repository.PermitRepository;
import com.chris64233.constructioninspection.repository.StopWorkOrderRepository;
import com.chris64233.constructioninspection.repository.WorkVersionRepository;
import com.chris64233.constructioninspection.support.BusinessException;
import com.chris64233.constructioninspection.support.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ApprovalService {

    private final PermitRepository permitRepository;
    private final ConstructionStageRepository stageRepository;
    private final WorkVersionRepository versionRepository;
    private final StopWorkOrderRepository stopWorkOrderRepository;
    private final FinalApprovalRepository finalApprovalRepository;

    public ApprovalService(PermitRepository permitRepository,
                           ConstructionStageRepository stageRepository,
                           WorkVersionRepository versionRepository,
                           StopWorkOrderRepository stopWorkOrderRepository,
                           FinalApprovalRepository finalApprovalRepository) {
        this.permitRepository = permitRepository;
        this.stageRepository = stageRepository;
        this.versionRepository = versionRepository;
        this.stopWorkOrderRepository = stopWorkOrderRepository;
        this.finalApprovalRepository = finalApprovalRepository;
    }

    /**
     * 最终使用批准：要求全部阶段验收完成且不存在活动停工令。
     * 与停工令签发共用许可行级悲观锁，二者并发时只形成"批准"或"被阻止"一种结果。
     * 批准记录一经形成不可修改。
     */
    @Transactional
    public FinalApproval approve(Long permitId) {
        Permit permit = permitRepository.findByIdForUpdate(permitId)
                .orElseThrow(() -> new NotFoundException("许可不存在: " + permitId));
        if (finalApprovalRepository.findByPermitId(permitId).isPresent()) {
            throw new BusinessException("许可已最终批准，批准记录不可修改");
        }
        long unfinished = stageRepository.countByPermitIdAndStatusNot(permitId, StageStatus.COMPLETED);
        if (unfinished > 0) {
            throw new BusinessException("存在 " + unfinished + " 个未完成阶段，不能最终批准");
        }
        long activeStopOrders = stopWorkOrderRepository.countByPermitIdAndStatus(permitId, StopWorkOrderStatus.ACTIVE);
        if (activeStopOrders > 0) {
            throw new BusinessException("存在活动停工令，不能最终批准");
        }
        String basis = buildBasis(permitId);
        permit.setStatus(PermitStatus.APPROVED);
        return finalApprovalRepository.save(new FinalApproval(permit, basis));
    }

    /**
     * 签发停工令。与最终批准互斥：许可已批准则不可再签发。
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

    /** 最终批准依据查询：批准记录 + 各阶段验收版本 + 停工令历史 */
    @Transactional(readOnly = true)
    public FinalApprovalBasis getApprovalBasis(Long permitId) {
        FinalApproval approval = finalApprovalRepository.findByPermitId(permitId)
                .orElseThrow(() -> new NotFoundException("许可尚未最终批准: " + permitId));
        List<StageAcceptance> stages = stageRepository.findByPermitIdOrderBySeq(permitId).stream()
                .map(stage -> {
                    Integer versionNumber = null;
                    if (stage.getAcceptedVersionId() != null) {
                        versionNumber = versionRepository.findById(stage.getAcceptedVersionId())
                                .map(WorkVersion::getVersionNumber)
                                .orElse(null);
                    }
                    return new StageAcceptance(stage.getSeq(), stage.getName(),
                            stage.getStatus().name(), versionNumber);
                })
                .toList();
        List<StopWorkOrder> orders = stopWorkOrderRepository.findByPermitIdOrderById(permitId);
        return new FinalApprovalBasis(approval, stages, orders);
    }

    private String buildBasis(Long permitId) {
        List<ConstructionStage> stages = stageRepository.findByPermitIdOrderBySeq(permitId);
        StringBuilder basis = new StringBuilder();
        basis.append("全部 ").append(stages.size()).append(" 个施工阶段已验收完成：");
        for (ConstructionStage stage : stages) {
            String version = stage.getAcceptedVersionId() == null ? "?"
                    : versionRepository.findById(stage.getAcceptedVersionId())
                            .map(v -> "v" + v.getVersionNumber())
                            .orElse("?");
            basis.append(stage.getName()).append("(验收版本 ").append(version).append(")；");
        }
        basis.append("批准时点无活动停工令。");
        return basis.toString();
    }

    public record StageAcceptance(int seq, String name, String status, Integer acceptedVersionNumber) {
    }

    public record FinalApprovalBasis(FinalApproval approval, List<StageAcceptance> stages,
                                     List<StopWorkOrder> stopWorkOrders) {
    }
}
