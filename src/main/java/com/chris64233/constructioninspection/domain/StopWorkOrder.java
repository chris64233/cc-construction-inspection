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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StopWorkOrderStatus status = StopWorkOrderStatus.ACTIVE;

    @Column(nullable = false, updatable = false)
    private Instant issuedAt = Instant.now();

    private Instant liftedAt;

    protected StopWorkOrder() {
    }

    public StopWorkOrder(Permit permit, String reason) {
        this.permit = permit;
        this.reason = reason;
    }

    public void lift() {
        this.status = StopWorkOrderStatus.LIFTED;
        this.liftedAt = Instant.now();
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

    public StopWorkOrderStatus getStatus() {
        return status;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public Instant getLiftedAt() {
        return liftedAt;
    }
}
