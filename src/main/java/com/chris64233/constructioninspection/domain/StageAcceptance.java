package com.chris64233.constructioninspection.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 阶段验收记录（每次验收追加一条，不更新、不删除）。
 * 完整关联"验收时的方案版本"与"验收依据的工作版本（复检结果）"，形成可追溯的验收依据链。
 * 一次阶段在被方案变更打回后重新验收，会产生新的验收记录。
 */
@Entity
@Table(name = "stage_acceptances")
public class StageAcceptance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stage_id", nullable = false)
    private ConstructionStage stage;

    /** 验收时许可所处的方案版本 */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_version_id", nullable = false)
    private PlanVersion planVersion;

    /** 验收依据的工作版本（该版本上所有检查项均为当前有效、且全部通过） */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "accepted_version_id", nullable = false)
    private WorkVersion acceptedVersion;

    @Column(nullable = false, updatable = false)
    private Instant acceptedAt = Instant.now();

    protected StageAcceptance() {
    }

    public StageAcceptance(ConstructionStage stage, PlanVersion planVersion, WorkVersion acceptedVersion) {
        this.stage = stage;
        this.planVersion = planVersion;
        this.acceptedVersion = acceptedVersion;
    }

    public Long getId() {
        return id;
    }

    public ConstructionStage getStage() {
        return stage;
    }

    public PlanVersion getPlanVersion() {
        return planVersion;
    }

    public WorkVersion getAcceptedVersion() {
        return acceptedVersion;
    }

    public Instant getAcceptedAt() {
        return acceptedAt;
    }
}
