package com.innbucks.userservice.support;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface SupportAccessLogRepository extends JpaRepository<SupportAccessLog, Long> {

    /** The SEARCH row that issued {@code lookupId} — unique by {@code uk_support_access_log_lookup}. */
    @Query("select l from SupportAccessLog l where l.lookupId = :lookupId and l.op = 'SEARCH'")
    Optional<SupportAccessLog> findSearch(@Param("lookupId") String lookupId);

    /** The retention sweep: one statement, idempotent, safe on N replicas at once. */
    @Modifying
    @Query("delete from SupportAccessLog l where l.createdAt < :cutoff")
    int deleteCreatedBefore(@Param("cutoff") LocalDateTime cutoff);
}
