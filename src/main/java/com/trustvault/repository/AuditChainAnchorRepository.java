package com.trustvault.repository;

import com.trustvault.entity.AuditChainAnchor;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AuditChainAnchorRepository extends JpaRepository<AuditChainAnchor, Integer> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AuditChainAnchor a where a.anchorId = :anchorId")
    Optional<AuditChainAnchor> findByIdForUpdate(@Param("anchorId") Integer anchorId);
}

