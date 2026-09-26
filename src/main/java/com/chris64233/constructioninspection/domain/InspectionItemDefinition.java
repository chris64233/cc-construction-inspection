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

/** 阶段所需检查项的定义 */
@Entity
@Table(name = "inspection_item_definitions",
        uniqueConstraints = @UniqueConstraint(columnNames = {"stage_id", "code"}))
public class InspectionItemDefinition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stage_id", nullable = false)
    private ConstructionStage stage;

    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    protected InspectionItemDefinition() {
    }

    public InspectionItemDefinition(ConstructionStage stage, String code, String name) {
        this.stage = stage;
        this.code = code;
        this.name = name;
    }

    public Long getId() {
        return id;
    }

    public ConstructionStage getStage() {
        return stage;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }
}
