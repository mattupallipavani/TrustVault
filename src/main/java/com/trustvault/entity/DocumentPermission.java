package com.trustvault.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "document_permissions",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_permission_document_user",
                columnNames = {"document_id", "user_id"}
        )
)
public class DocumentPermission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id", nullable = false)
    private Document document;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /**
     * Permission level, constrained to READ or EDIT. Stored as a string to
     * keep the schema readable; every write path validates the value against
     * {@link com.trustvault.enums.PermissionLevel}.
     */
    @Column(nullable = false, length = 20)
    private String permission;

    // Legacy/non-null database column retained for schema compatibility.
    // Current sharing restrictions are stored separately in document_share_restrictions.
    @Column(name = "share_privilege", nullable = false, length = 20)
    private String sharePrivilege = "";

    @Column(nullable = false, updatable = false)
    private LocalDateTime grantedAt;

    public DocumentPermission() {
    }

    @PrePersist
    protected void onCreate() {
        if (grantedAt == null) {
            grantedAt = LocalDateTime.now();
        }
        if (sharePrivilege == null) {
            sharePrivilege = "";
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

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public String getPermission() {
        return permission;
    }

    public void setPermission(String permission) {
        this.permission = permission;
    }

    public String getSharePrivilege() {
        return sharePrivilege;
    }

    public void setSharePrivilege(String sharePrivilege) {
        this.sharePrivilege = sharePrivilege;
    }

    public LocalDateTime getGrantedAt() {
        return grantedAt;
    }

    public void setGrantedAt(LocalDateTime grantedAt) {
        this.grantedAt = grantedAt;
    }
}
