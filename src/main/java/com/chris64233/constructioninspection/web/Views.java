package com.chris64233.constructioninspection.web;

import com.chris64233.constructioninspection.domain.Amendment;
import com.chris64233.constructioninspection.domain.ConstructionStage;
import com.chris64233.constructioninspection.domain.InspectionItemDefinition;
import com.chris64233.constructioninspection.domain.InspectionRecord;
import com.chris64233.constructioninspection.domain.Permit;
import com.chris64233.constructioninspection.domain.PlanVersion;
import com.chris64233.constructioninspection.domain.Rectification;
import com.chris64233.constructioninspection.domain.StopWorkOrder;
import com.chris64233.constructioninspection.domain.WorkVersion;

import java.time.Instant;
import java.util.List;

/** Web 层视图 DTO，避免直接序列化 JPA 实体 */
public final class Views {

    private Views() {
    }

    public record PermitView(Long id, String name, String status, int currentPlanVersion, Instant createdAt) {
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

    public record VersionView(Long id, int versionNumber, String reason, Integer planVersionNumber,
                              Instant createdAt, List<RecordView> records) {
        public static VersionView of(WorkVersion v, List<InspectionRecord> records) {
            return new VersionView(v.getId(), v.getVersionNumber(), v.getReason().name(),
                    v.getPlanVersion().getVersionNumber(), v.getCreatedAt(),
                    records.stream().map(RecordView::of).toList());
        }
    }

    public record RecordView(Long id, String submissionNo, Long itemDefinitionId, String itemCode,
                             String conclusion, String inspector, String evidence, Instant createdAt) {
        public static RecordView of(InspectionRecord r) {
            return new RecordView(r.getId(), r.getSubmissionNo(), r.getItemDefinition().getId(),
                    r.getItemDefinition().getCode(), r.getConclusion().name(), r.getInspector(),
                    r.getEvidence(), r.getCreatedAt());
        }
    }

    public record RectificationView(Long id, String status, Long sourceRecordId, String itemCode,
                                    Integer sourceVersionNumber, Integer resultVersionNumber,
                                    String note, Instant createdAt, Instant closedAt) {
        public static RectificationView of(Rectification r) {
            return new RectificationView(r.getId(), r.getStatus().name(), r.getSourceRecord().getId(),
                    r.getSourceRecord().getItemDefinition().getCode(),
                    r.getSourceRecord().getVersion().getVersionNumber(),
                    r.getResultVersion() == null ? null : r.getResultVersion().getVersionNumber(),
                    r.getNote(), r.getCreatedAt(), r.getClosedAt());
        }
    }

    public record StopWorkOrderView(Long id, String reason, String status, Instant issuedAt, Instant liftedAt) {
        public static StopWorkOrderView of(StopWorkOrder o) {
            return new StopWorkOrderView(o.getId(), o.getReason(), o.getStatus().name(),
                    o.getIssuedAt(), o.getLiftedAt());
        }
    }

    public record AmendmentView(Long id, Long permitId, String description, String status,
                                List<Long> affectedStageIds, Integer planVersionNumber,
                                Instant createdAt, Instant approvedAt) {
        public static AmendmentView of(Amendment a, PlanVersion planVersion) {
            return new AmendmentView(a.getId(), a.getPermit().getId(), a.getDescription(), a.getStatus().name(),
                    a.getAffectedStageIds(),
                    planVersion == null ? null : planVersion.getVersionNumber(),
                    a.getCreatedAt(), a.getApprovedAt());
        }
    }

    public record PlanVersionView(Long id, int versionNumber, Long amendmentId, Instant createdAt) {
        public static PlanVersionView of(PlanVersion v) {
            return new PlanVersionView(v.getId(), v.getVersionNumber(),
                    v.getAmendment() == null ? null : v.getAmendment().getId(), v.getCreatedAt());
        }
    }
}
