package com.trustvault.service;

import com.trustvault.entity.Document;
import com.trustvault.entity.DocumentPermission;
import com.trustvault.entity.User;
import com.trustvault.repository.DocumentPermissionRepository;
import com.trustvault.repository.DocumentRepository;
import com.trustvault.repository.UserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PermissionServiceTest {

    private static final Long DOCUMENT_ID = 10L;
    private static final Long OWNER_ID = 1L;
    private static final Long OTHER_ID = 2L;

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private DocumentPermissionRepository permissionRepository;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private PermissionService permissionService;

    private Document document;
    private User owner;
    private User other;

    @BeforeEach
    void setUp() {

        document = new Document();
        document.setId(DOCUMENT_ID);
        document.setOwnerId(OWNER_ID);
        document.setFileName("contract.txt");

        owner = new User();
        owner.setId(OWNER_ID);
        owner.setEmail("owner@trustvault.local");
        owner.setRole(com.trustvault.enums.Role.OWNER);

        other = new User();
        other.setId(OTHER_ID);
        other.setEmail("other@trustvault.local");
        other.setRole(com.trustvault.enums.Role.VIEWER);
    }

    @Test
    void ownerHasReadAndEdit() {

        when(documentRepository.findById(DOCUMENT_ID))
                .thenReturn(Optional.of(document));

        assertTrue(permissionService.canRead(DOCUMENT_ID, OWNER_ID));
        assertTrue(permissionService.canEdit(DOCUMENT_ID, OWNER_ID));
    }

    @Test
    void userWithNoGrantHasNothing() {

        when(documentRepository.findById(DOCUMENT_ID))
                .thenReturn(Optional.of(document));
        when(permissionRepository
                .findByDocumentIdAndUserId(DOCUMENT_ID, OTHER_ID))
                .thenReturn(Optional.empty());

        assertFalse(permissionService.canRead(DOCUMENT_ID, OTHER_ID));
        assertFalse(permissionService.canEdit(DOCUMENT_ID, OTHER_ID));
    }

    @Test
    void readGrantDoesNotAllowEdit() {

        when(documentRepository.findById(DOCUMENT_ID))
                .thenReturn(Optional.of(document));
        when(permissionRepository
                .findByDocumentIdAndUserId(DOCUMENT_ID, OTHER_ID))
                .thenReturn(Optional.of(grant("READ")));

        assertTrue(permissionService.canRead(DOCUMENT_ID, OTHER_ID));
        assertFalse(permissionService.canEdit(DOCUMENT_ID, OTHER_ID));
    }

    @Test
    void editGrantImpliesRead() {

        when(documentRepository.findById(DOCUMENT_ID))
                .thenReturn(Optional.of(document));
        when(permissionRepository
                .findByDocumentIdAndUserId(DOCUMENT_ID, OTHER_ID))
                .thenReturn(Optional.of(grant("EDIT")));

        assertTrue(permissionService.canRead(DOCUMENT_ID, OTHER_ID));
        assertTrue(permissionService.canEdit(DOCUMENT_ID, OTHER_ID));
    }

    @Test
    void requireReadRefusesAndAuditsWhenNoGrantExists() {

        when(documentRepository.findById(DOCUMENT_ID))
                .thenReturn(Optional.of(document));
        when(permissionRepository
                .findByDocumentIdAndUserId(DOCUMENT_ID, OTHER_ID))
                .thenReturn(Optional.empty());

        assertThrows(AccessDeniedException.class,
                () -> permissionService.requireRead(DOCUMENT_ID, other));

        verify(auditLogService).denied(any());
    }

    @Test
    void requireOwnerRefusesNonOwnerAndAudits() {

        when(documentRepository.findById(DOCUMENT_ID))
                .thenReturn(Optional.of(document));

        assertThrows(AccessDeniedException.class,
                () -> permissionService.requireOwner(DOCUMENT_ID, other));

        verify(auditLogService).denied(any());
    }

    @Test
    void revokeRemovesTheGrantRow() {

        when(documentRepository.findById(DOCUMENT_ID))
                .thenReturn(Optional.of(document));
        when(userRepository.findById(OTHER_ID))
                .thenReturn(Optional.of(other));
        when(permissionRepository
                .deleteForDocumentAndUser(DOCUMENT_ID, OTHER_ID))
                .thenReturn(1);

        permissionService.revokePermission(DOCUMENT_ID, owner, OTHER_ID);

        verify(permissionRepository)
                .deleteForDocumentAndUser(DOCUMENT_ID, OTHER_ID);
        verify(auditLogService).success(any());
    }

    @Test
    void unknownPermissionValueFailsClosed() {

        when(documentRepository.findById(DOCUMENT_ID))
                .thenReturn(Optional.of(document));
        when(permissionRepository
                .findByDocumentIdAndUserId(DOCUMENT_ID, OTHER_ID))
                .thenReturn(Optional.of(grant("SUPERUSER")));

        assertFalse(permissionService.canRead(DOCUMENT_ID, OTHER_ID));
        assertFalse(permissionService.canEdit(DOCUMENT_ID, OTHER_ID));
    }

    private DocumentPermission grant(String level) {
        DocumentPermission permission = new DocumentPermission();
        permission.setDocument(document);
        permission.setUser(other);
        permission.setPermission(level);
        return permission;
    }
}
