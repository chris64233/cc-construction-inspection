package com.chris64233.constructioninspection.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "permits")
public class Permit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "permit_no", nullable = false, unique = true)
    private String permitNo;

    @Column(name = "project_name", nullable = false)
    private String projectName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PermitStatus status = PermitStatus.ACTIVE;

    @OneToMany(mappedBy = "permit", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sequence ASC")
    private List<Stage> stages = new ArrayList<>();

    protected Permit() {
    }

    public Permit(String permitNo, String projectName) {
        this.permitNo = permitNo;
        this.projectName = projectName;
    }

    public void addStage(Stage stage) {
        stages.add(stage);
        stage.setPermit(this);
    }

    public void approve() {
        this.status = PermitStatus.APPROVED;
    }

    public Long getId() {
        return id;
    }

    public String getPermitNo() {
        return permitNo;
    }

    public String getProjectName() {
        return projectName;
    }

    public PermitStatus getStatus() {
        return status;
    }

    public List<Stage> getStages() {
        return stages;
    }
}
