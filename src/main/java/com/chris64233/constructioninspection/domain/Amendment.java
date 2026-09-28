package com.chris64233.constructioninspection.domain;

import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 方案变更单。提交时记录变更说明与受影响施工阶段（可只影响部分阶段）；
 * 批准时基于 basePlanVersionNumber 生成新方案版本，仅受影响阶段的当前有效检查结果失效。
 */
@Entity
@Table(name = "amendments")
public class Amendment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "permit_id", nullable = false)
    private Permit permit;

    @Column(nullable = false, length = 2000)
    private String summary;

    /** 乐观版本字段：批准时校验其是否仍为当前方案，拒绝过期变更 */
    @Column(name = "base_plan_version_number", nullable = false)
    private int basePlanVersionNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AmendmentStatus status = AmendmentStatus.PROPOSED;

    @OneToMany(mappedBy = "amendment", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("stageSeq ASC")
    private List<AmendmentAffectedStage> affectedStages = new ArrayList<>();

    /** 批准后生成的方案版本号；提交时为 null */
    private Integer resultPlanVersionNumber;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    private Instant approvedAt;

    protected Amendment() {
    }

    public Amendment(Permit permit, String summary, int basePlanVersionNumber) {
        this.permit = permit;
        this.summary = summary;
        this.basePlanVersionNumber = basePlanVersionNumber;
    }

    public void addAffectedStage(ConstructionStage stage) {
        affectedStages.add(new AmendmentAffectedStage(this, stage, stage.getSeq()));
    }

    public void approve(int resultPlanVersionNumber) {
        this.status = AmendmentStatus.APPROVED;
        this.resultPlanVersionNumber = resultPlanVersionNumber;
        this.approvedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Permit getPermit() {
        return permit;
    }

    public String getSummary() {
        return summary;
    }

    public int getBasePlanVersionNumber() {
        return basePlanVersionNumber;
    }

    public AmendmentStatus getStatus() {
        return status;
    }

    public List<AmendmentAffectedStage> getAffectedStages() {
        return affectedStages;
    }

    public Integer getResultPlanVersionNumber() {
        return resultPlanVersionNumber;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }
}
