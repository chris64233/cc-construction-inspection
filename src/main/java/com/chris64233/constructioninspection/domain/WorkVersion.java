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

/** 被检查的工程版本：阶段激活时产生 v1，整改提交产生复检版本，方案变更批准产生变更版本 */
@Entity
@Table(name = "work_versions",
        uniqueConstraints = @UniqueConstraint(columnNames = {"stage_id", "version_number"}))
public class WorkVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stage_id", nullable = false)
    private ConstructionStage stage;

    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private VersionReason reason;

    /** 产生本工程版本时生效的方案版本 */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_version_id", nullable = false)
    private PlanVersion planVersion;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected WorkVersion() {
    }

    public WorkVersion(ConstructionStage stage, int versionNumber, VersionReason reason,
                       PlanVersion planVersion) {
        this.stage = stage;
        this.versionNumber = versionNumber;
        this.reason = reason;
        this.planVersion = planVersion;
    }

    public Long getId() {
        return id;
    }

    public ConstructionStage getStage() {
        return stage;
    }

    public int getVersionNumber() {
        return versionNumber;
    }

    public VersionReason getReason() {
        return reason;
    }

    public PlanVersion getPlanVersion() {
        return planVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
