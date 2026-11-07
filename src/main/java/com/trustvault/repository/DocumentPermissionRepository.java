package com.trustvault.repository;

import com.trustvault.entity.DocumentPermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface DocumentPermissionRepository
        extends JpaRepository<DocumentPermission, Long> {

    List<DocumentPermission> findByDocumentId(Long documentId);

    List<DocumentPermission> findByUserId(Long userId);

    Optional<DocumentPermission> findByDocumentIdAndUserId(
            Long documentId, Long userId);

    boolean existsByDocumentIdAndUserId(Long documentId, Long userId);

    @Modifying
    @Transactional
    @Query("delete from DocumentPermission p where p.document.id = :documentId")
    int deleteAllForDocument(@Param("documentId") Long documentId);

    @Modifying
    @Transactional
    @Query("""
            delete from DocumentPermission p
            where p.document.id = :documentId and p.user.id = :userId
            """)
    int deleteForDocumentAndUser(
            @Param("documentId") Long documentId,
            @Param("userId") Long userId);
}
