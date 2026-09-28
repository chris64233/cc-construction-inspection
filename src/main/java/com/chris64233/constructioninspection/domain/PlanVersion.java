package com.chris64233.constructioninspection.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * 方案版本：许可创建时生成 v1；每次变更批准生成下一个版本。
 * 工程版本（WorkVersion）通过 planVersion 关联到产生它的方案版本，
 * 形成"方案版本 → 工程版本 → 检查记录"的完整关联链。
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

    /** 产生本方案版本的变更单；初始版本 v1 为 null */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "amendment_id")
    private Amendment amendment;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected PlanVersion() {
    }

    public PlanVersion(Permit permit, int versionNumber, Amendment amendment) {
        this.permit = permit;
        this.versionNumber = versionNumber;
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

    public Amendment getAmendment() {
        return amendment;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
