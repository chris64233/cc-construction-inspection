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

@Entity
@Table(name = "stop_work_orders")
public class StopWorkOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "permit_id", nullable = false)
    private Permit permit;

    @Column(nullable = false, length = 1000)
    private String reason;

    @Column(nullable = false)
    private String issuer;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StopOrderStatus status = StopOrderStatus.ACTIVE;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "lifted_at")
    private Instant liftedAt;

    protected StopWorkOrder() {
    }

    public StopWorkOrder(Permit permit, String reason, String issuer, Instant issuedAt) {
        this.permit = permit;
        this.reason = reason;
        this.issuer = issuer;
        this.issuedAt = issuedAt;
    }

    public void lift(Instant when) {
        this.status = StopOrderStatus.LIFTED;
        this.liftedAt = when;
    }

    public Long getId() {
        return id;
    }

    public Permit getPermit() {
        return permit;
    }

    public String getReason() {
        return reason;
    }

    public String getIssuer() {
        return issuer;
    }

    public StopOrderStatus getStatus() {
        return status;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public Instant getLiftedAt() {
        return liftedAt;
    }
}
