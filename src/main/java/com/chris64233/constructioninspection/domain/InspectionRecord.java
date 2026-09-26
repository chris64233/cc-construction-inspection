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
 * 检查记录。唯一约束保证：
 * 1. (stage_id, submission_no) 幂等——同一提交号只产生一条记录；
 * 2. (stage_id, item_code, version) 一个检查项在同一工程版本只有一个生效结论。
 */
@Entity
@Table(name = "inspection_records", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"stage_id", "submission_no"}),
        @UniqueConstraint(columnNames = {"stage_id", "item_code", "version"})
})
public class InspectionRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stage_id", nullable = false)
    private Stage stage;

    @Column(name = "item_code", nullable = false)
    private String itemCode;

    @Column(nullable = false)
    private int version;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Conclusion conclusion;

    @Column(nullable = false)
    private String inspector;

    @Column(nullable = false, length = 2000)
    private String evidence;

    @Column(name = "submission_no", nullable = false)
    private String submissionNo;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected InspectionRecord() {
    }

    public InspectionRecord(Stage stage, String itemCode, int version, Conclusion conclusion,
                            String inspector, String evidence, String submissionNo, Instant createdAt) {
        this.stage = stage;
        this.itemCode = itemCode;
        this.version = version;
        this.conclusion = conclusion;
        this.inspector = inspector;
        this.evidence = evidence;
        this.submissionNo = submissionNo;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Stage getStage() {
        return stage;
    }

    public String getItemCode() {
        return itemCode;
    }

    public int getVersion() {
        return version;
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

    public String getSubmissionNo() {
        return submissionNo;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
