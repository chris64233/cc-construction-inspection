package com.chris64233.constructioninspection.service;

import com.chris64233.constructioninspection.domain.FinalApproval;
import com.chris64233.constructioninspection.domain.Permit;
import com.chris64233.constructioninspection.domain.PermitStatus;
import com.chris64233.constructioninspection.domain.Stage;
import com.chris64233.constructioninspection.domain.StageStatus;
import com.chris64233.constructioninspection.domain.StopOrderStatus;
import com.chris64233.constructioninspection.domain.StopWorkOrder;
import com.chris64233.constructioninspection.repo.FinalApprovalRepository;
import com.chris64233.constructioninspection.repo.PermitRepository;
import com.chris64233.constructioninspection.repo.StageRepository;
import com.chris64233.constructioninspection.repo.StopWorkOrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 停工令与最终使用批准。批准与停工令签发都先对许可行加悲观写锁，
 * 并发时二者被串行化，最终只会形成"已批准"或"被停工令阻止"其中一种结果。
 */
@Service
public class ApprovalService {

    private final PermitRepository permitRepository;
    private final StageRepository stageRepository;
    private final StopWorkOrderRepository stopOrderRepository;
    private final FinalApprovalRepository approvalRepository;

    public ApprovalService(PermitRepository permitRepository,
                           StageRepository stageRepository,
                           StopWorkOrderRepository stopOrderRepository,
                           FinalApprovalRepository approvalRepository) {
        this.permitRepository = permitRepository;
        this.stageRepository = stageRepository;
        this.stopOrderRepository = stopOrderRepository;
        this.approvalRepository = approvalRepository;
    }

    @Transactional
    public StopWorkOrder issueStopOrder(Long permitId, String reason, String issuer) {
        Permit permit = lockPermit(permitId);
        if (permit.getStatus() == PermitStatus.APPROVED) {
            // 已批准记录不可修改：批准后不能再签发停工令
            throw BusinessException.conflict("PERMIT_APPROVED", "许可已获最终批准，不能再签发停工令");
        }
        return stopOrderRepository.save(new StopWorkOrder(permit, reason, issuer, Instant.now()));
    }

    @Transactional
    public StopWorkOrder liftStopOrder(Long permitId, Long orderId) {
        lockPermit(permitId);
        StopWorkOrder order = stopOrderRepository.findByIdAndPermitId(orderId, permitId)
                .orElseThrow(() -> BusinessException.notFound("停工令不存在: " + orderId));
        if (order.getStatus() == StopOrderStatus.LIFTED) {
            return order;
        }
        order.lift(Instant.now());
        return order;
    }

    /**
     * 最终使用批准：全部阶段验收完成且不存在活动停工令。
     * 重复申请返回已存在的批准记录（幂等），批准记录创建后不可修改。
     */
    @Transactional
    public FinalApproval approveFinal(Long permitId, String approvedBy) {
        Permit permit = lockPermit(permitId);
        Optional<FinalApproval> existing = approvalRepository.findByPermitId(permitId);
        if (existing.isPresent()) {
            return existing.get();
        }
        long incomplete = stageRepository.countByPermitIdAndStatusNot(permitId, StageStatus.ACCEPTED);
        if (incomplete > 0) {
            throw BusinessException.unprocessable("STAGES_INCOMPLETE",
                    "存在 " + incomplete + " 个未验收完成的阶段，不能批准");
        }
        if (stopOrderRepository.existsByPermitIdAndStatus(permitId, StopOrderStatus.ACTIVE)) {
            throw BusinessException.conflict("ACTIVE_STOP_ORDER", "存在活动停工令，不能批准");
        }
        FinalApproval approval = approvalRepository.save(new FinalApproval(permit, approvedBy, Instant.now()));
        permit.approve();
        return approval;
    }

    public record ApprovalBasis(FinalApproval approval, List<Stage> stages, List<StopWorkOrder> stopOrders) {
    }

    /** 最终批准依据查询：批准记录 + 各阶段验收情况 + 停工令历史 */
    @Transactional(readOnly = true)
    public ApprovalBasis getApprovalBasis(Long permitId) {
        FinalApproval approval = approvalRepository.findByPermitId(permitId)
                .orElseThrow(() -> BusinessException.notFound("许可尚未获得最终批准: " + permitId));
        List<Stage> stages = stageRepository.findByPermitIdOrderBySequenceAsc(permitId);
        List<StopWorkOrder> orders = stopOrderRepository.findByPermitIdOrderByIssuedAtAscIdAsc(permitId);
        return new ApprovalBasis(approval, stages, orders);
    }

    private Permit lockPermit(Long permitId) {
        return permitRepository.findByIdForUpdate(permitId)
                .orElseThrow(() -> BusinessException.notFound("许可不存在: " + permitId));
    }
}
