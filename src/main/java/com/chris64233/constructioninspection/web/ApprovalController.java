package com.chris64233.constructioninspection.web;

import com.chris64233.constructioninspection.service.ApprovalService;
import com.chris64233.constructioninspection.web.dto.Requests.ApproveRequest;
import com.chris64233.constructioninspection.web.dto.Requests.IssueStopOrderRequest;
import com.chris64233.constructioninspection.web.dto.Responses.ApprovalBasisView;
import com.chris64233.constructioninspection.web.dto.Responses.ApprovalView;
import com.chris64233.constructioninspection.web.dto.Responses.StopOrderView;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/permits/{permitId}")
public class ApprovalController {

    private final ApprovalService approvalService;

    public ApprovalController(ApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    @PostMapping("/stop-orders")
    @ResponseStatus(HttpStatus.CREATED)
    public StopOrderView issueStopOrder(@PathVariable Long permitId,
                                        @Valid @RequestBody IssueStopOrderRequest request) {
        return StopOrderView.of(approvalService.issueStopOrder(permitId, request.reason(), request.issuer()));
    }

    @PostMapping("/stop-orders/{orderId}/lift")
    public StopOrderView liftStopOrder(@PathVariable Long permitId, @PathVariable Long orderId) {
        return StopOrderView.of(approvalService.liftStopOrder(permitId, orderId));
    }

    @PostMapping("/final-approval")
    @ResponseStatus(HttpStatus.CREATED)
    public ApprovalView approve(@PathVariable Long permitId,
                                @Valid @RequestBody ApproveRequest request) {
        return ApprovalView.of(approvalService.approveFinal(permitId, request.approvedBy()));
    }

    @GetMapping("/final-approval")
    public ApprovalBasisView approvalBasis(@PathVariable Long permitId) {
        return ApprovalBasisView.of(approvalService.getApprovalBasis(permitId));
    }
}
