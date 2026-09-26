package com.chris64233.constructioninspection.domain;

import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "stages", uniqueConstraints = @UniqueConstraint(columnNames = {"permit_id", "sequence"}))
public class Stage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "permit_id", nullable = false)
    private Permit permit;

    @Column(nullable = false)
    private int sequence;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StageStatus status = StageStatus.PENDING;

    /** 当前工程版本，整改提交后递增，复检只针对当前版本 */
    @Column(name = "current_version", nullable = false)
    private int currentVersion = 1;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @OneToMany(mappedBy = "stage", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("itemCode ASC")
    private List<StageItem> items = new ArrayList<>();

    protected Stage() {
    }

    public Stage(int sequence, String name) {
        this.sequence = sequence;
        this.name = name;
    }

    public void addItem(StageItem item) {
        items.add(item);
        item.setStage(this);
    }

    public boolean hasItem(String itemCode) {
        return items.stream().anyMatch(i -> i.getItemCode().equals(itemCode));
    }

    public void bumpVersion() {
        this.currentVersion++;
    }

    public void accept(Instant when) {
        this.status = StageStatus.ACCEPTED;
        this.acceptedAt = when;
    }

    public Long getId() {
        return id;
    }

    public Permit getPermit() {
        return permit;
    }

    void setPermit(Permit permit) {
        this.permit = permit;
    }

    public int getSequence() {
        return sequence;
    }

    public String getName() {
        return name;
    }

    public StageStatus getStatus() {
        return status;
    }

    public int getCurrentVersion() {
        return currentVersion;
    }

    public Instant getAcceptedAt() {
        return acceptedAt;
    }

    public List<StageItem> getItems() {
        return items;
    }
}
