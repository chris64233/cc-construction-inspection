package com.chris64233.constructioninspection.web.dto;

import com.chris64233.constructioninspection.domain.Conclusion;
import com.chris64233.constructioninspection.domain.FinalApproval;
import com.chris64233.constructioninspection.domain.InspectionRecord;
import com.chris64233.constructioninspection.domain.Permit;
import com.chris64233.constructioninspection.domain.PermitStatus;
import com.chris64233.constructioninspection.domain.Rectification;
import com.chris64233.constructioninspection.domain.RectificationStatus;
import com.chris64233.constructioninspection.domain.Stage;
import com.chris64233.constructioninspection.domain.StageItem;
import com.chris64233.constructioninspection.domain.StageStatus;
import com.chris64233.constructioninspection.domain.StopOrderStatus;
import com.chris64233.constructioninspection.domain.StopWorkOrder;
import com.chris64233.constructioninspection.service.ApprovalService;
import com.chris64233.constructioninspection.service.InspectionService;

import java.time.Instant;
import java.util.List;

public final class Responses {

    private Responses() {
    }

    public record PermitView(Long id, String permitNo, String projectName, PermitStatus status,
                             List<StageView> stages) {
        public static PermitView of(Permit permit, List<Stage> stages) {
            return new PermitView(permit.getId(), permit.getPermitNo(), permit.getProjectName(),
                    permit.getStatus(), stages.stream().map(StageView::of).toList());
        }
    }

    public record StageView(Long id, int sequence, String name, StageStatus status,
                            int currentVersion, Instant acceptedAt, List<ItemView> items) {
        public static StageView of(Stage stage) {
            return new StageView(stage.getId(), stage.getSequence(), stage.getName(), stage.getStatus(),
                    stage.getCurrentVersion(), stage.getAcceptedAt(),
                    stage.getItems().stream().map(ItemView::of).toList());
        }
    }

    public record ItemView(String itemCode, String name) {
        public static ItemView of(StageItem item) {
            return new ItemView(item.getItemCode(), item.getName());
        }
    }

    public record InspectionView(Long id, String itemCode, int version, Conclusion conclusion,
                                 String inspector, String evidence, String submissionNo, Instant createdAt) {
        public static InspectionView of(InspectionRecord r) {
            return new InspectionView(r.getId(), r.getItemCode(), r.getVersion(), r.getConclusion(),
                    r.getInspector(), r.getEvidence(), r.getSubmissionNo(), r.getCreatedAt());
        }
    }

    public record InspectionResultView(InspectionView inspection, RectificationView rectification,
                                       boolean idempotentReplay) {
        public static InspectionResultView of(InspectionService.InspectionResult result) {
            return new InspectionResultView(InspectionView.of(result.record()),
                    result.rectification() == null ? null : RectificationView.of(result.rectification()),
                    result.idempotentReplay());
        }
    }

    public record RectificationView(Long id, String itemCode, int raisedVersion, String description,
                                    RectificationStatus status, String submissionNo, Instant submittedAt,
                                    Integer closedVersion, Instant closedAt, Instant createdAt) {
        public static RectificationView of(Rectification r) {
            return new RectificationView(r.getId(), r.getItemCode(), r.getRaisedVersion(), r.getDescription(),
                    r.getStatus(), r.getSubmissionNo(), r.getSubmittedAt(), r.getClosedVersion(),
                    r.getClosedAt(), r.getCreatedAt());
        }
    }

    public record StopOrderView(Long id, String reason, String issuer, StopOrderStatus status,
                                Instant issuedAt, Instant liftedAt) {
        public static StopOrderView of(StopWorkOrder o) {
            return new StopOrderView(o.getId(), o.getReason(), o.getIssuer(), o.getStatus(),
                    o.getIssuedAt(), o.getLiftedAt());
        }
    }

    public record ApprovalView(Long id, String approvedBy, Instant approvedAt) {
        public static ApprovalView of(FinalApproval a) {
            return new ApprovalView(a.getId(), a.getApprovedBy(), a.getApprovedAt());
        }
    }

    public record ApprovalBasisView(ApprovalView approval, List<StageView> stages,
                                    List<StopOrderView> stopOrders) {
        public static ApprovalBasisView of(ApprovalService.ApprovalBasis basis) {
            return new ApprovalBasisView(ApprovalView.of(basis.approval()),
                    basis.stages().stream().map(StageView::of).toList(),
                    basis.stopOrders().stream().map(StopOrderView::of).toList());
        }
    }
}
