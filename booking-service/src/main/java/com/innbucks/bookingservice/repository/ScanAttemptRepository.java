package com.innbucks.bookingservice.repository;

import com.innbucks.bookingservice.dto.scan.OutcomeCount;
import com.innbucks.bookingservice.dto.scan.TeamMemberOutcomeCount;
import com.innbucks.bookingservice.entity.ScanAttempt;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read model for {@code scan_attempts}.
 *
 * <p>The listing methods are bounded by an explicit time window — callers MUST
 * supply both {@code from} and {@code to}. An unbounded scan over the table is
 * not exposed at the API; the controller validates the window.
 *
 * <p>The aggregation projections (constructor-expression queries) require the
 * FQ class names on the {@code SELECT new ...} clauses to match the actual DTO
 * locations — moving the records to a different package breaks them at startup.
 */
public interface ScanAttemptRepository extends JpaRepository<ScanAttempt, UUID> {

    Page<ScanAttempt> findByScannerUserUuidAndAttemptedAtBetween(UUID scanner,
                                                                 Instant from,
                                                                 Instant to,
                                                                 Pageable pg);

    Page<ScanAttempt> findByEventIdAndAttemptedAtBetween(UUID eventId,
                                                         Instant from,
                                                         Instant to,
                                                         Pageable pg);

    Page<ScanAttempt> findByScannerOrganizerUuidAndAttemptedAtBetween(UUID organizer,
                                                                      Instant from,
                                                                      Instant to,
                                                                      Pageable pg);

    @Query("""
        SELECT new com.innbucks.bookingservice.dto.scan.OutcomeCount(s.outcome, COUNT(s))
        FROM ScanAttempt s
        WHERE s.scannerUserUuid = :scanner
          AND s.attemptedAt BETWEEN :from AND :to
        GROUP BY s.outcome
    """)
    List<OutcomeCount> countOutcomesForScanner(@Param("scanner") UUID scanner,
                                               @Param("from") Instant from,
                                               @Param("to") Instant to);

    @Query("""
        SELECT new com.innbucks.bookingservice.dto.scan.OutcomeCount(s.outcome, COUNT(s))
        FROM ScanAttempt s
        WHERE s.eventId = :eventId
          AND s.attemptedAt BETWEEN :from AND :to
        GROUP BY s.outcome
    """)
    List<OutcomeCount> countOutcomesForEvent(@Param("eventId") UUID eventId,
                                             @Param("from") Instant from,
                                             @Param("to") Instant to);

    @Query("""
        SELECT new com.innbucks.bookingservice.dto.scan.TeamMemberOutcomeCount(
            s.scannerUserUuid, s.scannerEmail, s.scannerDisplayName, s.outcome, COUNT(s))
        FROM ScanAttempt s
        WHERE s.scannerOrganizerUuid = :organizer
          AND s.attemptedAt BETWEEN :from AND :to
          AND s.scannerUserUuid IS NOT NULL
        GROUP BY s.scannerUserUuid, s.scannerEmail, s.scannerDisplayName, s.outcome
    """)
    List<TeamMemberOutcomeCount> countOutcomesPerTeamMember(@Param("organizer") UUID organizer,
                                                            @Param("from") Instant from,
                                                            @Param("to") Instant to);
}
