package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.dto.scan.EventScanStatsDTO;
import com.innbucks.bookingservice.dto.scan.OutcomeCount;
import com.innbucks.bookingservice.dto.scan.PageResponse;
import com.innbucks.bookingservice.dto.scan.ScanAttemptDTO;
import com.innbucks.bookingservice.dto.scan.ScannerStatsDTO;
import com.innbucks.bookingservice.dto.scan.TeamMemberOutcomeCount;
import com.innbucks.bookingservice.dto.scan.TeamMemberStatsDTO;
import com.innbucks.bookingservice.dto.scan.TeamStatsResponseDTO;
import com.innbucks.bookingservice.entity.ScanAttempt;
import com.innbucks.bookingservice.repository.ScanAttemptRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Read-side for the scan-attempts audit log — drives the organizer dashboard.
 *
 * <p>Three responsibilities:
 *
 * <ul>
 *   <li>Page through {@code scan_attempts} for a given scope (scanner / event /
 *       organizer) over a time window — the listings the FE renders.</li>
 *   <li>Aggregate per-outcome counts and zero-fill missing keys, so the FE
 *       can render every Outcome enum value without conditional logic.</li>
 *   <li>Collapse per-team-member rows (one per {@code scannerUserUuid +
 *       outcome}) into a leaderboard ordered by total scans DESC.</li>
 * </ul>
 *
 * <p>All methods are {@code readOnly = true} — this service never writes.
 * The audit write happens in {@link TicketScanService#scan}.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ScanReportService {

    private final ScanAttemptRepository repository;

    @Transactional(readOnly = true)
    public PageResponse<ScanAttemptDTO> listMyScans(UUID scannerUserUuid,
                                                    Instant from,
                                                    Instant to,
                                                    int page,
                                                    int size) {
        Page<ScanAttempt> pg = repository.findByScannerUserUuidAndAttemptedAtBetween(
                scannerUserUuid, from, to, pageable(page, size));
        return PageResponse.from(pg.map(ScanReportService::toDto));
    }

    @Transactional(readOnly = true)
    public PageResponse<ScanAttemptDTO> listEventScans(UUID eventId,
                                                       Instant from,
                                                       Instant to,
                                                       int page,
                                                       int size) {
        Page<ScanAttempt> pg = repository.findByEventIdAndAttemptedAtBetween(
                eventId, from, to, pageable(page, size));
        return PageResponse.from(pg.map(ScanReportService::toDto));
    }

    @Transactional(readOnly = true)
    public ScannerStatsDTO myStats(UUID scannerUserUuid,
                                   String scannerEmail,
                                   String scannerDisplayName,
                                   Instant from,
                                   Instant to) {
        List<OutcomeCount> rows = repository.countOutcomesForScanner(scannerUserUuid, from, to);
        Map<String, Long> byOutcome = zeroFilledOutcomes(rows);
        long total = byOutcome.values().stream().mapToLong(Long::longValue).sum();
        return new ScannerStatsDTO(scannerUserUuid, scannerEmail, scannerDisplayName,
                from, to, total, byOutcome);
    }

    @Transactional(readOnly = true)
    public EventScanStatsDTO eventStats(UUID eventId, Instant from, Instant to) {
        List<OutcomeCount> rows = repository.countOutcomesForEvent(eventId, from, to);
        Map<String, Long> byOutcome = zeroFilledOutcomes(rows);
        long total = byOutcome.values().stream().mapToLong(Long::longValue).sum();
        return new EventScanStatsDTO(eventId, from, to, total, byOutcome);
    }

    @Transactional(readOnly = true)
    // organizerUuid == null means fleet-wide (SUPER_ADMIN caller) — the
    // repository treats a null organizer as "all organizers' gate staff".
    public TeamStatsResponseDTO teamStats(@org.springframework.lang.Nullable UUID organizerUuid, Instant from, Instant to) {
        List<TeamMemberOutcomeCount> rows =
                repository.countOutcomesPerTeamMember(organizerUuid, from, to);

        // Collapse rows keyed on scannerUserUuid. Multiple rows for the same
        // user_uuid happen when the staff member's email or display name
        // changed mid-window — we keep the freshest non-null identity per uuid.
        Map<UUID, TeamAccumulator> byUser = new LinkedHashMap<>();
        for (TeamMemberOutcomeCount r : rows) {
            byUser.computeIfAbsent(r.scannerUserUuid(), id -> new TeamAccumulator())
                    .add(r);
        }

        List<TeamMemberStatsDTO> members = byUser.entrySet().stream()
                .map(e -> e.getValue().toDto(e.getKey()))
                // Leaderboard: most active scanner first.
                .sorted(Comparator.comparingLong(TeamMemberStatsDTO::total).reversed())
                .collect(Collectors.toList());
        return new TeamStatsResponseDTO(from, to, members);
    }

    /**
     * Zero-fill every Outcome enum value so the FE doesn't have to defend
     * against missing keys. Order follows enum declaration order.
     */
    private static Map<String, Long> zeroFilledOutcomes(List<OutcomeCount> rows) {
        Map<ScanAttempt.Outcome, Long> indexed = rows.stream()
                .filter(r -> r.outcome() != null)
                .collect(Collectors.toMap(OutcomeCount::outcome,
                        r -> r.count() == null ? 0L : r.count(),
                        Long::sum));
        Map<String, Long> out = new LinkedHashMap<>();
        for (ScanAttempt.Outcome o : ScanAttempt.Outcome.values()) {
            out.put(o.name(), indexed.getOrDefault(o, 0L));
        }
        return out;
    }

    private static ScanAttemptDTO toDto(ScanAttempt s) {
        return new ScanAttemptDTO(
                s.getId(),
                s.getAttemptedAt(),
                s.getOutcome() == null ? null : s.getOutcome().name(),
                s.getTicketNumber(),
                s.getBookingItemId(),
                s.getBookingId(),
                s.getEventId(),
                s.getScannerEmail(),
                s.getScannerDisplayName(),
                s.getScannerUserUuid()
        );
    }

    private static Pageable pageable(int page, int size) {
        return PageRequest.of(page, size, Sort.by("attemptedAt").descending());
    }

    /** Per-user outcome accumulator used to collapse the leaderboard query. */
    private static final class TeamAccumulator {
        private final Map<String, Long> byOutcome = new LinkedHashMap<>();
        private long total;
        private String freshestEmail;
        private String freshestDisplayName;

        void add(TeamMemberOutcomeCount r) {
            long c = r.count() == null ? 0L : r.count();
            String key = r.outcome() == null ? null : r.outcome().name();
            if (key != null) {
                byOutcome.merge(key, c, Long::sum);
            }
            total += c;
            // Pick the freshest non-null identity. Both fields are nullable
            // (legacy tokens, missing display name), so we just take the
            // first non-null we see and skip nulls thereafter.
            if (freshestEmail == null && r.scannerEmail() != null) {
                freshestEmail = r.scannerEmail();
            }
            if (freshestDisplayName == null && r.scannerDisplayName() != null) {
                freshestDisplayName = r.scannerDisplayName();
            }
        }

        TeamMemberStatsDTO toDto(UUID userUuid) {
            // Zero-fill the outcome map even for the leaderboard row.
            Map<String, Long> filled = new LinkedHashMap<>();
            for (ScanAttempt.Outcome o : ScanAttempt.Outcome.values()) {
                filled.put(o.name(), byOutcome.getOrDefault(o.name(), 0L));
            }
            return new TeamMemberStatsDTO(userUuid, freshestEmail, freshestDisplayName, total, filled);
        }
    }
}
