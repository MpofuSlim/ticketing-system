package com.innbucks.eventservice.repository;

import com.innbucks.eventservice.entity.EventTermsVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface EventTermsVersionRepository extends JpaRepository<EventTermsVersion, UUID> {

    Optional<EventTermsVersion> findByEventIdAndVersion(UUID eventId, int version);

    /** The highest version ever written for the event, removed terms included; 0 when none. */
    @Query("SELECT COALESCE(MAX(t.version), 0) FROM EventTermsVersion t WHERE t.eventId = :eventId")
    int maxVersion(@Param("eventId") UUID eventId);
}
