package com.trustvault.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Records that an administrator has forbidden a specific user from sharing
 * (granting permissions on) a specific document.
 *
 * This is separate from access grants: a user may still READ or EDIT a
 * document but be prevented from sharing it with others. The primary use
 * case is an admin restricting an Owner from sharing their own document.
 */
@Entity
@Table(
        name = "sharing_restrictions",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_sharing_restriction_document_user",
                columnNames = {"document_id", "restricted_user_id"}
        ),
        indexes = {
                @Index(name = "idx_sharing_restriction_document",
                        columnList = "document_id"),
                @Index(name = "idx_sharing_restriction_user",
                        columnList = "restricted_user_id")
        }
)
public class SharingRestriction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id", nullable = false)
    private Document document;

    /**
     * The user who is restricted from sharing this document.
     */
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "restricted_user_id", nullable = false)
    private User restrictedUser;

    /**
     * The administrator who imposed the restriction.
     */
    @Column(nullable = false)
    private Long restrictedById;

    @Column(nullable = false, length = 150)
    private String restrictedByEmail;

    @Column(nullable = false, updatable = false)
    private LocalDateTime restrictedAt;

    public SharingRestriction() {
    }

    @PrePersist
    protected void onCreate() {
        if (restrictedAt == null) {
            restrictedAt = LocalDateTime.now();
        }
    }

    public Long getId() {
        return id;
    }

    public Document getDocument() {
        return document;
    }

    public void setDocument(Document document) {
        this.document = document;
    }

    public User getRestrictedUser() {
        return restrictedUser;
    }

    public void setRestrictedUser(User restrictedUser) {
        this.restrictedUser = restrictedUser;
    }

    public Long getRestrictedById() {
        return restrictedById;
    }

    public void setRestrictedById(Long restrictedById) {
        this.restrictedById = restrictedById;
    }

    public String getRestrictedByEmail() {
        return restrictedByEmail;
    }

    public void setRestrictedByEmail(String restrictedByEmail) {
        this.restrictedByEmail = restrictedByEmail;
    }

    public LocalDateTime getRestrictedAt() {
        return restrictedAt;
    }
}

