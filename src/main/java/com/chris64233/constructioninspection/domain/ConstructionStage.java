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

@Entity
@Table(name = "construction_stages",
        uniqueConstraints = @UniqueConstraint(columnNames = {"permit_id", "seq"}))
public class ConstructionStage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "permit_id", nullable = false)
    private Permit permit;

    /** 阶段顺序，从 1 开始，验收必须按序进行 */
    @Column(nullable = false)
    private int seq;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StageStatus status = StageStatus.PENDING;

    /** 验收通过时基于的工程版本 */
    private Long acceptedVersionId;

    protected ConstructionStage() {
    }

    public ConstructionStage(Permit permit, int seq, String name, StageStatus status) {
        this.permit = permit;
        this.seq = seq;
        this.name = name;
        this.status = status;
    }

    public Long getId() {
        return id;
    }

    public Permit getPermit() {
        return permit;
    }

    public int getSeq() {
        return seq;
    }

    public String getName() {
        return name;
    }

    public StageStatus getStatus() {
        return status;
    }

    public void setStatus(StageStatus status) {
        this.status = status;
    }

    public Long getAcceptedVersionId() {
        return acceptedVersionId;
    }

    public void setAcceptedVersionId(Long acceptedVersionId) {
        this.acceptedVersionId = acceptedVersionId;
    }
}
