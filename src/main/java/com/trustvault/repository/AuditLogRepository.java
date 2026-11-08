package com.trustvault.repository;

import com.trustvault.entity.AuditLog;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<AuditLog> findTopByOrderByIdDesc();

    List<AuditLog> findByDocumentIdOrderByIdDesc(Long documentId);

    List<AuditLog> findByActorUserIdOrderByIdDesc(Long actorUserId);

    List<AuditLog> findAllByOrderByIdDesc();

    Page<AuditLog> findAllByOrderByIdDesc(Pageable pageable);

    @Query("""
            select a from AuditLog a
            where (cast(:action as String) is null or a.action = :action)
              and (cast(:outcome as String) is null or a.outcome = :outcome)
              and (cast(:documentId as Long) is null or a.documentId = :documentId)
              and (cast(:actorEmail as String) is null or lower(a.actorEmail) = lower(cast(:actorEmail as String)))
              and (cast(:from as LocalDateTime) is null or a.createdAt >= :from)
              and (cast(:to as LocalDateTime) is null or a.createdAt <= :to)
            order by a.id desc
            """)
    List<AuditLog> search(
            @Param("action") String action,
            @Param("outcome") String outcome,
            @Param("documentId") Long documentId,
            @Param("actorEmail") String actorEmail,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to
    );

    @Query("""
            select a from AuditLog a
            where (cast(:action as String) is null or a.action = :action)
              and (cast(:outcome as String) is null or a.outcome = :outcome)
              and (cast(:documentId as Long) is null or a.documentId = :documentId)
              and (cast(:actorEmail as String) is null or lower(a.actorEmail) = lower(cast(:actorEmail as String)))
              and (cast(:from as LocalDateTime) is null or a.createdAt >= :from)
              and (cast(:to as LocalDateTime) is null or a.createdAt <= :to)
            order by a.id desc
            """)
    Page<AuditLog> searchPage(
            @Param("action") String action,
            @Param("outcome") String outcome,
            @Param("documentId") Long documentId,
            @Param("actorEmail") String actorEmail,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            Pageable pageable
    );

    long countByOutcome(String outcome);
}
