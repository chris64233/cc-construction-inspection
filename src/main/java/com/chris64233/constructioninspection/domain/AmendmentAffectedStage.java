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
import jakarta.persistence.UniqueConstraint;

/**
 * 变更单与受影响施工阶段的关联（多对多）。
 * stageSeq 冗余阶段顺序，便于按序处理与展示，避免阶段行被联表加载。
 */
@Entity
@Table(name = "amendment_affected_stages",
        uniqueConstraints = @UniqueConstraint(columnNames = {"amendment_id", "stage_id"}))
public class AmendmentAffectedStage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "amendment_id", nullable = false)
    private Amendment amendment;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stage_id", nullable = false)
    private ConstructionStage stage;

    @Column(name = "stage_seq", nullable = false)
    private int stageSeq;

    protected AmendmentAffectedStage() {
    }

    public AmendmentAffectedStage(Amendment amendment, ConstructionStage stage, int stageSeq) {
        this.amendment = amendment;
        this.stage = stage;
        this.stageSeq = stageSeq;
    }

    public Long getId() {
        return id;
    }

    public Amendment getAmendment() {
        return amendment;
    }

    public ConstructionStage getStage() {
        return stage;
    }

    public int getStageSeq() {
        return stageSeq;
    }
}
