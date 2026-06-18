package com.innbucks.bookingservice.dto.scan;

import com.innbucks.bookingservice.entity.ScanAttempt;

import java.util.UUID;

/**
 * JPA constructor-expression target for the per-team-member
 * {@code GROUP BY scannerUserUuid, outcome} query. Same FQ-name contract
 * as {@link OutcomeCount}.
 *
 * <p>scannerEmail / scannerDisplayName may differ across rows for the
 * same scannerUserUuid if the staff member's email / display name was
 * updated between scans — the aggregation in the service collapses
 * outcomes per user_uuid and picks the freshest non-null identity to
 * present, so the leaderboard shows one row per person.
 */
public record TeamMemberOutcomeCount(
        UUID scannerUserUuid,
        String scannerEmail,
        String scannerDisplayName,
        ScanAttempt.Outcome outcome,
        Long count
) { }
