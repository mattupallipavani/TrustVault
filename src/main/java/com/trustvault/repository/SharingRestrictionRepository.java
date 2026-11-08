package com.trustvault.repository;

import com.trustvault.entity.SharingRestriction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface SharingRestrictionRepository
        extends JpaRepository<SharingRestriction, Long> {

    List<SharingRestriction> findByDocumentId(Long documentId);

    Optional<SharingRestriction> findByDocumentIdAndRestrictedUserId(
            Long documentId, Long restrictedUserId);

    boolean existsByDocumentIdAndRestrictedUserId(
            Long documentId, Long restrictedUserId);

    @Modifying
    @Transactional
    @Query("""
            delete from SharingRestriction r
            where r.document.id = :documentId
              and r.restrictedUser.id = :userId
            """)
    int deleteByDocumentIdAndUserId(
            @Param("documentId") Long documentId,
            @Param("userId") Long userId);
}

