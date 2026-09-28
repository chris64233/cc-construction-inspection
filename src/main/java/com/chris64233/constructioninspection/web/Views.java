package com.chris64233.constructioninspection.web;

import com.chris64233.constructioninspection.domain.Amendment;
import com.chris64233.constructioninspection.domain.ConstructionStage;
import com.chris64233.constructioninspection.domain.InspectionItemDefinition;
import com.chris64233.constructioninspection.domain.InspectionRecord;
import com.chris64233.constructioninspection.domain.Permit;
import com.chris64233.constructioninspection.domain.Rectification;
import com.chris64233.constructioninspection.domain.StageAcceptance;
import com.chris64233.constructioninspection.domain.StopWorkOrder;
import com.chris64233.constructioninspection.domain.WorkVersion;

import java.time.Instant;
import java.util.List;

/** Web 层视图 DTO，避免直接序列化 JPA 实体 */
public final class Views {

    private Views() {
    }

    public record PermitView(Long id, String name, String status, int currentPlanVersionNumber,
                             Instant createdAt) {
        public static PermitView of(Permit p) {
            return new PermitView(p.getId(), p.getName(), p.getStatus().name(),
                    p.getCurrentPlanVersionNumber(), p.getCreatedAt());
        }
    }

    public record StageView(Long id, int seq, String name, String status, Long acceptedVersionId,
                            List<ItemDefView> items) {
        public static StageView of(ConstructionStage s, List<InspectionItemDefinition> items) {
            return new StageView(s.getId(), s.getSeq(), s.getName(), s.getStatus().name(),
                    s.getAcceptedVersionId(), items.stream().map(ItemDefView::of).toList());
        }
    }

    public record ItemDefView(Long id, String code, String name) {
        public static ItemDefView of(InspectionItemDefinition d) {
            return new ItemDefView(d.getId(), d.getCode(), d.getName());
        }
    }

    public record VersionView(Long id, int versionNumber, int planVersionNumber, String reason,
                              Instant createdAt, List<RecordView> records) {
        public static VersionView of(WorkVersion v, List<InspectionRecord> records) {
            return new VersionView(v.getId(), v.getVersionNumber(), v.getPlanVersionNumber(),
                    v.getReason().name(), v.getCreatedAt(),
                    records.stream().map(RecordView::of).toList());
        }
    }

    public record RecordView(Long id, String submissionNo, Long itemDefinitionId, String itemCode,
                             String conclusion, boolean invalidated, Integer invalidatedByPlanVersion,
                             String inspector, String evidence, Instant createdAt) {
        public static RecordView of(InspectionRecord r) {
            return new RecordView(r.getId(), r.getSubmissionNo(), r.getItemDefinition().getId(),
                    r.getItemDefinition().getCode(), r.getConclusion().name(),
                    r.isInvalidated(), r.getInvalidatedByPlanVersion(),
                    r.getInspector(), r.getEvidence(), r.getCreatedAt());
        }
    }

    public record RectificationView(Long id, String status, Long sourceRecordId, String itemCode,
                                    Integer sourceVersionNumber, Integer resultVersionNumber,
                                    Integer cancelledByPlanVersion,
                                    String note, Instant createdAt, Instant closedAt) {
        public static RectificationView of(Rectification r) {
            return new RectificationView(r.getId(), r.getStatus().name(), r.getSourceRecord().getId(),
                    r.getSourceRecord().getItemDefinition().getCode(),
                    r.getSourceRecord().getVersion().getVersionNumber(),
                    r.getResultVersion() == null ? null : r.getResultVersion().getVersionNumber(),
                    r.getCancelledByPlanVersion(),
                    r.getNote(), r.getCreatedAt(), r.getClosedAt());
        }
    }

    public record AcceptanceView(Long id, int planVersionNumber, int acceptedVersionNumber,
                                 Instant acceptedAt) {
        public static AcceptanceView of(StageAcceptance a) {
            return new AcceptanceView(a.getId(), a.getPlanVersion().getVersionNumber(),
                    a.getAcceptedVersion().getVersionNumber(), a.getAcceptedAt());
        }
    }

    public record AmendmentView(Long id, Long permitId, String summary, String status,
                                int basePlanVersionNumber, Integer resultPlanVersionNumber,
                                Instant createdAt, Instant approvedAt,
                                List<AffectedStageView> affectedStages) {
        public static AmendmentView of(Amendment a, List<AffectedStageView> affectedStages) {
            return new AmendmentView(a.getId(), a.getPermit().getId(), a.getSummary(),
                    a.getStatus().name(), a.getBasePlanVersionNumber(), a.getResultPlanVersionNumber(),
                    a.getCreatedAt(), a.getApprovedAt(), affectedStages);
        }
    }

    public record AffectedStageView(Long stageId, int stageSeq, String stageName) {
    }

    /** 变更批准后每个受影响阶段的处理结果：哪些结果失效、是否必须复检 */
    public record AmendmentEffectView(Long stageId, int stageSeq, String stageName,
                                      String statusBefore, String statusAfter,
                                      int invalidatedRecordCount, int cancelledRectificationCount,
                                      Integer newWorkVersionNumber, boolean mustReinspect) {
    }

    public record AmendmentApprovalView(AmendmentView amendment, int planVersionNumber,
                                        List<AmendmentEffectView> effects) {
    }

    public record StopWorkOrderView(Long id, String reason, String status, Instant issuedAt, Instant liftedAt) {
        public static StopWorkOrderView of(StopWorkOrder o) {
            return new StopWorkOrderView(o.getId(), o.getReason(), o.getStatus().name(),
                    o.getIssuedAt(), o.getLiftedAt());
        }
    }
}
