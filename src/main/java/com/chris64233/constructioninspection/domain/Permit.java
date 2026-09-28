package com.chris64233.constructioninspection.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "permits")
public class Permit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PermitStatus status = PermitStatus.IN_PROGRESS;

    /** 当前生效的方案版本号：创建时为 1，每次变更批准递增 */
    @Column(nullable = false)
    private int currentPlanVersionNumber = 1;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Permit() {
    }

    public Permit(String name) {
        this.name = name;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public PermitStatus getStatus() {
        return status;
    }

    public void setStatus(PermitStatus status) {
        this.status = status;
    }

    public int getCurrentPlanVersionNumber() {
        return currentPlanVersionNumber;
    }

    public void setCurrentPlanVersionNumber(int currentPlanVersionNumber) {
        this.currentPlanVersionNumber = currentPlanVersionNumber;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
