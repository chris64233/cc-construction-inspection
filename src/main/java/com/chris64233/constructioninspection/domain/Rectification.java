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

import java.time.Instant;

/**
 * 整改项：由不通过的检查记录自动生成。
 * 整改提交后关闭，并产生新的复检版本（resultVersion），形成整改链。
 */
@Entity
@Table(name = "rectifications")
public class Rectification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stage_id", nullable = false)
    private ConstructionStage stage;

    /** 触发本整改项的不通过检查记录 */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_record_id", nullable = false)
    private InspectionRecord sourceRecord;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RectificationStatus status = RectificationStatus.OPEN;

    /** 整改提交说明 */
    @Column(length = 2000)
    private String note;

    /** 整改提交后产生的复检版本 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "result_version_id")
    private WorkVersion resultVersion;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    private Instant closedAt;

    /** 因方案变更被取消时的方案版本号；未取消为 null */
    @Column(name = "cancelled_by_plan_version")
    private Integer cancelledByPlanVersion;

    protected Rectification() {
    }

    public Rectification(ConstructionStage stage, InspectionRecord sourceRecord) {
        this.stage = stage;
        this.sourceRecord = sourceRecord;
    }

    public void close(String note, WorkVersion resultVersion) {
        this.status = RectificationStatus.CLOSED;
        this.note = note;
        this.resultVersion = resultVersion;
        this.closedAt = Instant.now();
    }

    /** 方案变更影响本阶段：未关闭整改项随旧方案结果一并取消 */
    public void cancel(int byPlanVersionNumber) {
        this.status = RectificationStatus.CANCELLED;
        this.cancelledByPlanVersion = byPlanVersionNumber;
        this.closedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public ConstructionStage getStage() {
        return stage;
    }

    public InspectionRecord getSourceRecord() {
        return sourceRecord;
    }

    public RectificationStatus getStatus() {
        return status;
    }

    public String getNote() {
        return note;
    }

    public WorkVersion getResultVersion() {
        return resultVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public Integer getCancelledByPlanVersion() {
        return cancelledByPlanVersion;
    }
}
