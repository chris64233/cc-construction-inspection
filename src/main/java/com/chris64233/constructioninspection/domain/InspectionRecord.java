package com.chris64233.constructioninspection.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * 检查记录。submissionNo 为幂等提交号（全局唯一）；
 * (version, itemDefinition) 唯一保证一个检查项在同一工程版本上只有一条生效结论。
 */
@Entity
@Table(name = "inspection_records",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"submission_no"}),
                @UniqueConstraint(columnNames = {"version_id", "item_definition_id"})
        })
public class InspectionRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "version_id", nullable = false)
    private WorkVersion version;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "item_definition_id", nullable = false)
    private InspectionItemDefinition itemDefinition;

    @Column(name = "submission_no", nullable = false)
    private String submissionNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Conclusion conclusion;

    @Column(nullable = false)
    private String inspector;

    @Column(nullable = false, length = 2000)
    private String evidence;

    /** 失效标记：false 为当前有效结果；方案变更使受影响阶段的旧结果置为 true（记录保留可追溯） */
    @Column(name = "invalidated", nullable = false)
    private boolean invalidated = false;

    /** 使本结果失效的方案版本号；未失效为 null */
    @Column(name = "invalidated_by_plan_version")
    private Integer invalidatedByPlanVersion;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected InspectionRecord() {
    }

    public InspectionRecord(WorkVersion version, InspectionItemDefinition itemDefinition,
                            String submissionNo, Conclusion conclusion, String inspector, String evidence) {
        this.version = version;
        this.itemDefinition = itemDefinition;
        this.submissionNo = submissionNo;
        this.conclusion = conclusion;
        this.inspector = inspector;
        this.evidence = evidence;
    }

    /** 方案变更批准时调用：记录本结果因哪个新方案版本失效 */
    public void invalidate(int byPlanVersionNumber) {
        this.invalidated = true;
        this.invalidatedByPlanVersion = byPlanVersionNumber;
    }

    public Long getId() {
        return id;
    }

    public WorkVersion getVersion() {
        return version;
    }

    public InspectionItemDefinition getItemDefinition() {
        return itemDefinition;
    }

    public String getSubmissionNo() {
        return submissionNo;
    }

    public Conclusion getConclusion() {
        return conclusion;
    }

    public String getInspector() {
        return inspector;
    }

    public String getEvidence() {
        return evidence;
    }

    public boolean isInvalidated() {
        return invalidated;
    }

    public Integer getInvalidatedByPlanVersion() {
        return invalidatedByPlanVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
