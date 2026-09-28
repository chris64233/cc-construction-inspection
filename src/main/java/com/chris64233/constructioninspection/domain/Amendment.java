package com.chris64233.constructioninspection.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
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
import java.util.ArrayList;
import java.util.List;

/**
 * 方案变更单：登记受影响的施工阶段，批准后生成新方案版本，
 * 受影响阶段的检查结果失效并须按新方案重新检查，未受影响阶段的有效结果保留。
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

    /** 变更说明 */
    @Column(nullable = false, length = 2000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AmendmentStatus status = AmendmentStatus.PENDING;

    /** 变更标明受影响的施工阶段 */
    @ElementCollection
    @CollectionTable(name = "amendment_affected_stages", joinColumns = @JoinColumn(name = "amendment_id"))
    @Column(name = "stage_id", nullable = false)
    private List<Long> affectedStageIds = new ArrayList<>();

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    private Instant approvedAt;

    protected Amendment() {
    }

    public Amendment(Permit permit, String description, List<Long> affectedStageIds) {
        this.permit = permit;
        this.description = description;
        this.affectedStageIds = new ArrayList<>(affectedStageIds);
    }

    public void approve() {
        this.status = AmendmentStatus.APPROVED;
        this.approvedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Permit getPermit() {
        return permit;
    }

    public String getDescription() {
        return description;
    }

    public AmendmentStatus getStatus() {
        return status;
    }

    public List<Long> getAffectedStageIds() {
        return affectedStageIds;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }
}
