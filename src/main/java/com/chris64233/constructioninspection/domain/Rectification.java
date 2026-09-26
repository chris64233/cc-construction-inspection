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
 * 整改项：不通过检查自动生成。整改提交（OPEN→SUBMITTED）使阶段工程版本 +1，
 * 复检通过后关闭（CLOSED）。(stage_id, submission_no) 唯一，保证整改提交幂等。
 */
@Entity
@Table(name = "rectifications",
        uniqueConstraints = @UniqueConstraint(columnNames = {"stage_id", "submission_no"}))
public class Rectification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stage_id", nullable = false)
    private Stage stage;

    @Column(name = "item_code", nullable = false)
    private String itemCode;

    /** 不通过结论所在的工程版本 */
    @Column(name = "raised_version", nullable = false)
    private int raisedVersion;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "inspection_record_id", nullable = false)
    private InspectionRecord inspectionRecord;

    @Column(nullable = false, length = 1000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RectificationStatus status = RectificationStatus.OPEN;

    @Column(name = "submission_no")
    private String submissionNo;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    /** 复检通过并关闭本整改项时所在的工程版本 */
    @Column(name = "closed_version")
    private Integer closedVersion;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Rectification() {
    }

    public Rectification(Stage stage, String itemCode, int raisedVersion,
                         InspectionRecord inspectionRecord, String description, Instant createdAt) {
        this.stage = stage;
        this.itemCode = itemCode;
        this.raisedVersion = raisedVersion;
        this.inspectionRecord = inspectionRecord;
        this.description = description;
        this.createdAt = createdAt;
    }

    public void markSubmitted(String submissionNo, Instant when) {
        this.status = RectificationStatus.SUBMITTED;
        this.submissionNo = submissionNo;
        this.submittedAt = when;
    }

    public void close(int closedVersion, Instant when) {
        this.status = RectificationStatus.CLOSED;
        this.closedVersion = closedVersion;
        this.closedAt = when;
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

    public int getRaisedVersion() {
        return raisedVersion;
    }

    public InspectionRecord getInspectionRecord() {
        return inspectionRecord;
    }

    public String getDescription() {
        return description;
    }

    public RectificationStatus getStatus() {
        return status;
    }

    public String getSubmissionNo() {
        return submissionNo;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public Integer getClosedVersion() {
        return closedVersion;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
