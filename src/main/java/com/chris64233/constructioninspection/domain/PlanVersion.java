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
 * 方案版本（许可级）。许可创建时生成 v1（INITIAL）；
 * 每次方案变更批准后产生 v2、v3…（AMENDMENT），并反向关联批准它的变更单。
 */
@Entity
@Table(name = "plan_versions",
        uniqueConstraints = @UniqueConstraint(columnNames = {"permit_id", "version_number"}))
public class PlanVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "permit_id", nullable = false)
    private Permit permit;

    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PlanVersionReason reason;

    /** AMENDMENT 版本由此变更单批准产生；INITIAL 版本为 null */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "amendment_id")
    private Amendment amendment;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected PlanVersion() {
    }

    public PlanVersion(Permit permit, int versionNumber, PlanVersionReason reason, Amendment amendment) {
        this.permit = permit;
        this.versionNumber = versionNumber;
        this.reason = reason;
        this.amendment = amendment;
    }

    public Long getId() {
        return id;
    }

    public Permit getPermit() {
        return permit;
    }

    public int getVersionNumber() {
        return versionNumber;
    }

    public PlanVersionReason getReason() {
        return reason;
    }

    public Amendment getAmendment() {
        return amendment;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
