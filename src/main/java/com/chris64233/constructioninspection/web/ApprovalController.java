package com.chris64233.constructioninspection.web;

import com.chris64233.constructioninspection.domain.FinalApproval;
import com.chris64233.constructioninspection.service.ApprovalService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api")
public class ApprovalController {

    private final ApprovalService approvalService;

    public ApprovalController(ApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    public record StopWorkOrderRequest(@NotBlank String reason) {
    }

    @PostMapping("/permits/{permitId}/stop-work-orders")
    @ResponseStatus(HttpStatus.CREATED)
    public Views.StopWorkOrderView issueStopWorkOrder(@PathVariable Long permitId,
                                                      @Valid @RequestBody StopWorkOrderRequest request) {
        return Views.StopWorkOrderView.of(approvalService.issueStopWorkOrder(permitId, request.reason()));
    }

    @PostMapping("/stop-work-orders/{orderId}/lift")
    public Views.StopWorkOrderView liftStopWorkOrder(@PathVariable Long orderId) {
        return Views.StopWorkOrderView.of(approvalService.liftStopWorkOrder(orderId));
    }

    public record FinalApprovalView(Long id, Long permitId, Instant approvedAt, String basis) {
    }

    /** 最终使用批准 */
    @PostMapping("/permits/{permitId}/final-approval")
    @ResponseStatus(HttpStatus.CREATED)
    public FinalApprovalView approve(@PathVariable Long permitId) {
        FinalApproval approval = approvalService.approve(permitId);
        return new FinalApprovalView(approval.getId(), permitId, approval.getApprovedAt(), approval.getBasis());
    }

    public record FinalApprovalBasisView(Long id, Long permitId, Instant approvedAt, String basis,
                                         List<ApprovalService.StageAcceptance> stages,
                                         List<Views.StopWorkOrderView> stopWorkOrders) {
    }

    /** 最终批准依据查询 */
    @GetMapping("/permits/{permitId}/final-approval")
    public FinalApprovalBasisView approvalBasis(@PathVariable Long permitId) {
        ApprovalService.FinalApprovalBasis basis = approvalService.getApprovalBasis(permitId);
        FinalApproval approval = basis.approval();
        return new FinalApprovalBasisView(approval.getId(), permitId, approval.getApprovedAt(),
                approval.getBasis(), basis.stages(),
                basis.stopWorkOrders().stream().map(Views.StopWorkOrderView::of).toList());
    }
}
