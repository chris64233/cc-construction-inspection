package com.chris64233.constructioninspection.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 最终使用批准。permit_id 唯一，一经批准不可修改（无更新入口，重复批准被拒绝）。
 */
@Entity
@Table(name = "final_approvals")
public class FinalApproval {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "permit_id", nullable = false, unique = true)
    private Permit permit;

    @Column(nullable = false, updatable = false)
    private Instant approvedAt = Instant.now();

    /** 批准依据快照：各阶段验收版本及停工令状态 */
    @Column(nullable = false, length = 4000)
    private String basis;

    protected FinalApproval() {
    }

    public FinalApproval(Permit permit, String basis) {
        this.permit = permit;
        this.basis = basis;
    }

    public Long getId() {
        return id;
    }

    public Permit getPermit() {
        return permit;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public String getBasis() {
        return basis;
    }
}
