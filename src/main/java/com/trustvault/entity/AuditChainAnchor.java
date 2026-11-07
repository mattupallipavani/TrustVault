package com.trustvault.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Anchor row for cluster-wide audit chain serialization.
 * Row 1 is locked with PESSIMISTIC_WRITE (SELECT ... FOR UPDATE) during every audit append,
 * guaranteeing consistent SHA-256 hash chaining across multiple application nodes.
 */
@Entity
@Table(name = "audit_chain_anchor")
public class AuditChainAnchor {

    @Id
    @Column(name = "anchor_id")
    private Integer anchorId = 1;

    @Column(name = "last_audit_id")
    private Long lastAuditId;

    @Column(name = "head_hash", nullable = false, length = 64)
    private String headHash;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public AuditChainAnchor() {
    }

    public Integer getAnchorId() {
        return anchorId;
    }

    public void setAnchorId(Integer anchorId) {
        this.anchorId = anchorId;
    }

    public Long getLastAuditId() {
        return lastAuditId;
    }

    public void setLastAuditId(Long lastAuditId) {
        this.lastAuditId = lastAuditId;
    }

    public String getHeadHash() {
        return headHash;
    }

    public void setHeadHash(String headHash) {
        this.headHash = headHash;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}

