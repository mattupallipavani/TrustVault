package com.trustvault.repository;

import com.trustvault.entity.Document;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface DocumentRepository extends JpaRepository<Document, Long> {

    List<Document> findByOwnerId(Long ownerId);

    @Query("""
            select d from Document d
            where d.ownerId = :userId
               or exists (
                    select 1 from DocumentPermission p
                    where p.document = d and p.user.id = :userId
               )
            order by d.uploadedAt desc, d.id desc
            """)
    List<Document> findAccessibleByUser(@Param("userId") Long userId);

    @Query("""
            select d from Document d
            where d.ownerId = :userId
               or exists (
                    select 1 from DocumentPermission p
                    where p.document = d and p.user.id = :userId
               )
            """)
    Page<Document> findAccessibleByUserPage(@Param("userId") Long userId, Pageable pageable);

    @Query("""
            select d from Document d
            where (
                    d.ownerId = :userId
                    or exists (
                        select 1 from DocumentPermission p
                        where p.document = d and p.user.id = :userId
                    )
                  )
              and (cast(:keyword as String) is null
                   or lower(d.fileName) like lower(concat('%', cast(:keyword as String), '%'))
                   or lower(d.category) like lower(concat('%', cast(:keyword as String), '%')))
              and (cast(:category as String) is null or lower(d.category) = lower(cast(:category as String)))
              and (cast(:owner as String) is null or lower(d.ownerEmail) = lower(cast(:owner as String)))
              and (cast(:from as LocalDateTime) is null or d.uploadedAt >= :from)
              and (cast(:to as LocalDateTime) is null or d.uploadedAt <= :to)
              and (:ownedOnly = false or d.ownerId = :userId)
              and (:sharedOnly = false or d.ownerId <> :userId)
            order by d.uploadedAt desc, d.id desc
            """)
    List<Document> searchAccessible(
            @Param("userId") Long userId,
            @Param("keyword") String keyword,
            @Param("category") String category,
            @Param("owner") String owner,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("ownedOnly") boolean ownedOnly,
            @Param("sharedOnly") boolean sharedOnly
    );

    @Query("""
            select d from Document d
            where (
                    d.ownerId = :userId
                    or exists (
                        select 1 from DocumentPermission p
                        where p.document = d and p.user.id = :userId
                    )
                  )
              and (cast(:keyword as String) is null
                   or lower(d.fileName) like lower(concat('%', cast(:keyword as String), '%'))
                   or lower(d.category) like lower(concat('%', cast(:keyword as String), '%')))
              and (cast(:category as String) is null or lower(d.category) = lower(cast(:category as String)))
              and (cast(:owner as String) is null or lower(d.ownerEmail) = lower(cast(:owner as String)))
              and (cast(:from as LocalDateTime) is null or d.uploadedAt >= :from)
              and (cast(:to as LocalDateTime) is null or d.uploadedAt <= :to)
              and (:ownedOnly = false or d.ownerId = :userId)
              and (:sharedOnly = false or d.ownerId <> :userId)
            """)
    Page<Document> searchAccessiblePage(
            @Param("userId") Long userId,
            @Param("keyword") String keyword,
            @Param("category") String category,
            @Param("owner") String owner,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("ownedOnly") boolean ownedOnly,
            @Param("sharedOnly") boolean sharedOnly,
            Pageable pageable
    );

    boolean existsByStorageKey(String storageKey);
}
