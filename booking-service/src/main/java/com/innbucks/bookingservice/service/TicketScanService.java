package com.innbucks.bookingservice.service;

import com.innbucks.common.ticket.TicketNumbers;
import com.innbucks.bookingservice.config.CountryMdcConfig;
import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.ScanTicketResponseDTO;
import com.innbucks.bookingservice.dto.ScannerAccessDTO;
import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.entity.BookingItem;
import com.innbucks.bookingservice.entity.ScanAttempt;
import com.innbucks.bookingservice.repository.BookingItemRepository;
import com.innbucks.bookingservice.repository.ScanAttemptRepository;
import com.innbucks.bookingservice.security.AuthenticatedCaller;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import com.innbucks.bookingservice.client.EventServiceClient;
import com.innbucks.bookingservice.config.MarketTimeZone;
import com.innbucks.bookingservice.dto.EventLookupDTO;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Single-shot ticket redemption at the gate, for two callers: the event's
 * organizer (EVENT_ORGANIZER, the organizer path) and gate scanners on the
 * InnBucks super app (a CUSTOMER session whose authority is event-service's
 * scanner membership, asked on every scan — {@link #scanForSuperApp}). No other
 * caller scans: the organizer-created TEAM_MEMBER gate accounts are retired
 * (owner decision, 2026-11-05), so a token carrying only that role is refused
 * by the controller and, should one ever reach this service, by
 * {@link #requireOrganizer} before anything is read.
 *
 * <p>Concurrency model: the claim is an atomic UPDATE WHERE redeemed_at
 * IS NULL — so two scanners pointing at the same QR can't both succeed.
 * The first call to {@link BookingItemRepository#claimRedemption} updates
 * 1 row; every subsequent call updates 0 rows and we re-load the row to
 * return the original {@code redeemedByName} + {@code redeemedAt} (the
 * "scanned by Tariro at 19:42" toast).
 *
 * <p>Authorization on the organizer path: the caller holds EVENT_ORGANIZER
 * and their {@code organizerUuid} JWT claim (their own {@code user_uuid}) equals
 * the booking's {@code tenant_user_uuid} (which mirrors the event's owning
 * organizer).
 *
 * <p><b>Transactions — no remote call is made while one is open.</b> The scan
 * runs in phases rather than under one method-level {@code @Transactional}:
 * <ol>
 *   <li>a short READ-ONLY transaction loads the ticket with its booking
 *       (fetch-joined, so the detached copy carries everything the gates
 *       read);</li>
 *   <li>the gates run with NO transaction: status, organizer ownership and the
 *       event-day rule (event-service); a super-app scanner's access
 *       (event-service) is asked before the read. A remote question used to
 *       hold the pooled connection for as long as the other service took to
 *       answer — a gate queue at a stadium is exactly the burst that turns that
 *       into pool exhaustion;</li>
 *   <li>one WRITE transaction then does the claim and its audit row together
 *       ({@link #claimAndAudit}); a refusal writes its audit row in a
 *       transaction of its own.</li>
 * </ol>
 * Nothing the gates decide is weakened by the split: the claim was never
 * protected by a lock taken at the read (the read in the old single
 * transaction was a plain READ COMMITTED select, so a concurrent change could
 * already land between it and the claim), and single-shot redemption is the
 * claim's own {@code WHERE redeemed_at IS NULL}, unchanged.
 *
 * <p><b>Audit invariant:</b> every scan attempt, regardless of outcome,
 * writes exactly one {@code scan_attempts} row. The audit insert shares
 * the same transaction as the {@code claimRedemption} UPDATE so a crash
 * mid-write rolls both back — and, being in that same committed transaction,
 * the row is queryable by the reporting endpoints the moment the scan response
 * is returned. There is no asynchronous hand-off and no propagation delay to
 * wait out.
 *
 * <p><b>What the audit try/catch does and does not buy.</b> It catches what
 * fails while BUILDING the row (a missing request context, an MDC lookup) and
 * whatever the persistence call itself throws. It does NOT make an audit write
 * unable to fail the scan: JPA defers the INSERT to flush-at-commit, which
 * happens after the catch has returned, so a constraint violation surfaces
 * at commit and rolls the redemption back with it. Genuinely isolating the two
 * would mean giving the audit write its own {@code REQUIRES_NEW} transaction,
 * which is a real trade — the row would then survive a rolled-back redemption
 * and claim an ALLOWED that never happened. That call has not been made; do
 * not read the catch block as though it had.
 */
@Service
@Slf4j
public class TicketScanService {

    private final BookingItemRepository bookingItemRepository;
    private final ScanAttemptRepository scanAttemptRepository;
    /** ObjectProvider so existing unit tests that build this service with
     *  `new` and pass null can continue to work; the audit counter is
     *  resolved lazily and silently skipped when no MeterRegistry bean is
     *  available. */
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;
    private final String deploymentCountry;
    /** ObjectProvider for the same reason as the meter registry: the existing
     *  unit tests build this service with `new`, and the event-day rule must
     *  not force every one of them to widen. A null provider means the check
     *  cannot run — see {@link #eventDayVerdict}, which treats that as
     *  UNVERIFIABLE and therefore obeys the fail-open flag rather than
     *  silently allowing. */
    private final ObjectProvider<EventServiceClient> eventServiceClientProvider;
    private final ObjectProvider<MarketTimeZone> marketTimeZoneProvider;

    /**
     * Resolved event windows, keyed by event id, held for 60s.
     *
     * <p>A gate queue scans the same event over and over, and without this
     * every scan would add a cross-service round trip to a path that made none
     * for organizers by design (tenantUserUuid was mirrored onto bookings
     * precisely to avoid one). 60s collapses a queue to roughly one lookup per
     * event per minute while bounding staleness after an organizer edits a
     * date to a minute.
     *
     * <p>Successes only. A failed lookup is never cached: caching it would turn
     * one blip into a guaranteed 60s of refusals, which is the opposite of what
     * the cache is for.
     */
    private final Map<UUID, CachedWindow> eventWindowCache = new ConcurrentHashMap<>();

    private record CachedWindow(LocalDateTime startUtc, LocalDateTime endUtc, long cachedAtMillis) {
    }

    private static final long EVENT_WINDOW_TTL_MILLIS = 60_000L;

    public TicketScanService(BookingItemRepository bookingItemRepository,
                             ScanAttemptRepository scanAttemptRepository,
                             ObjectProvider<MeterRegistry> meterRegistryProvider,
                             @Value("${innbucks.country:ZW}") String deploymentCountry,
                             ObjectProvider<EventServiceClient> eventServiceClientProvider,
                             ObjectProvider<MarketTimeZone> marketTimeZoneProvider) {
        this.bookingItemRepository = bookingItemRepository;
        this.scanAttemptRepository = scanAttemptRepository;
        this.meterRegistryProvider = meterRegistryProvider;
        this.deploymentCountry = deploymentCountry;
        this.eventServiceClientProvider = eventServiceClientProvider;
        this.marketTimeZoneProvider = marketTimeZoneProvider;
    }

    /**
     * The scan's phases (see the class javadoc). A setter so the many
     * plain-{@code new} unit tests keep their construction; Spring always
     * calls it, and with none set the phases run inline.
     */
    private TransactionTemplate readTx;
    private TransactionTemplate writeTx;

    @Autowired
    void setTransactionManager(PlatformTransactionManager transactionManager) {
        this.readTx = TransactionPhases.readOnly(transactionManager);
        this.writeTx = TransactionPhases.readWrite(transactionManager);
    }

    /** Shared S2S secret for event-service's internal lookups (the event's
     *  window and a super-app scanner's access). */
    @Value("${innbucks.internal-api-token:}")
    private String internalToken;

    /**
     * Kill switch for the event-day rule. true (default) = a ticket only
     * redeems on one of its event's market-local days.
     *
     * <p>Set false to disable the rule entirely — the break-glass lever when
     * event-service is degraded for longer than a gate can wait and the
     * fail-closed behaviour below is stopping a live event. Prefer this to
     * flipping fail-open: turning the rule off is an honest, loggable
     * statement that the check is not running, whereas fail-open leaves a rule
     * that silently evaporates exactly when it is least verifiable.
     */
    @Value("${innbucks.scan.event-day-check.enabled:true}")
    private boolean eventDayCheckEnabled;

    /**
     * What to do when the event's dates cannot be established — event-service
     * unreachable, circuit open, or a payload with no start.
     *
     * <p><b>Fail CLOSED by default</b> (false): refuse with a retryable 503 and
     * do NOT redeem. The asymmetry decides it — a refused scan is recoverable
     * by retrying seconds later, whereas an allowed wrong-day scan sets
     * {@code redeemed_at} and {@code BookingItemRepository} has no inverse, so
     * the mistake is permanent and burns a ticket that was valid for its own
     * day. Same posture as the seat-allocation and category-delete guards: an
     * unanswerable question is refused, never assumed safe.
     *
     * <p>true = break-glass: treat an unverifiable window as on-day and let the
     * scan proceed. Time-boxed operator decision only.
     */
    @Value("${innbucks.scan.event-day-check.fail-open:false}")
    private boolean eventDayCheckFailOpen;

    /**
     * Deliberately NOT {@code @Transactional}: the event-service lookups below
     * run with no transaction open. See the class javadoc for the phases.
     */
    public ScanTicketResponseDTO scan(String ticketNumber, String scannerDisplayName) {
        return scan(ticketNumber, null, scannerDisplayName);
    }

    /**
     * @param eventId the event the scanner is scanning for. Required from a
     *                super-app scanner ({@link #isSuperAppScanner}); optional for
     *                the organizer. When given, a ticket for any other event
     *                is {@code WRONG_EVENT} and is not redeemed.
     */
    public ScanTicketResponseDTO scan(String ticketNumber, UUID eventId, String scannerDisplayName) {
        long start = System.currentTimeMillis();

        if (ticketNumber == null || ticketNumber.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ticketNumber is required");
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (isSuperAppScanner(auth)) {
            return scanForSuperApp(ticketNumber, eventId, auth, start);
        }
        UUID scannerOrganizerUuid = AuthenticatedCaller.organizerUuid(auth);
        UUID scannerUserUuid = AuthenticatedCaller.userUuid(auth);
        String scannerEmail = auth == null ? null : auth.getName();
        if (scannerOrganizerUuid == null && scannerEmail == null) {
            // Defence in depth — the controller's @PreAuthorize already
            // requires authentication; this path is only reachable from a
            // wired-wrong test. NOT an audit-recordable attempt (no caller
            // identity to attribute it to).
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        // Defence in depth, before anything is read: the controller admits only
        // EVENT_ORGANIZER and CUSTOMER, and a CUSTOMER without the organizer
        // role took the super-app branch above. Without this, the organizerUuid
        // claim match below would be the only gate for any other token that
        // reached the service — a retired TEAM_MEMBER token carries its
        // organizer's uuid in that claim.
        requireOrganizer(auth);

        // Phase 1: a short read-only transaction. The booking is fetch-joined,
        // so the detached item still answers every gate below (status,
        // tenantUserUuid, eventId, holderName) once the transaction has closed.
        BookingItem item = TransactionPhases.inTransaction(readTx,
                () -> bookingItemRepository.findByTicketNumberWithBooking(ticketNumber))
                .orElse(null);
        if (item == null) {
            log.info("Ticket scan miss ticketNumber={} scanner={}", TicketNumbers.forLog(ticketNumber), scannerEmail);
            ScanTicketResponseDTO result = ScanTicketResponseDTO.builder()
                    .status(ScanTicketResponseDTO.Status.TICKET_NOT_FOUND)
                    .ticketNumber(ticketNumber)
                    .build();
            recordRefusal(ticketNumber, null, ScanAttempt.Outcome.TICKET_NOT_FOUND,
                    scannerOrganizerUuid, scannerUserUuid, scannerEmail, scannerDisplayName, start, eventId);
            return result;
        }

        // Phase 2: the gates, with no transaction open — the event-day rule
        // asks event-service.
        Booking booking = item.getBooking();
        if (booking.getStatus() != Booking.BookingStatus.CONFIRMED) {
            log.info("Ticket scan rejected, booking not confirmed ticketNumber={} status={} scanner={}",
                    TicketNumbers.forLog(ticketNumber), booking.getStatus(), scannerEmail);
            ScanTicketResponseDTO result = ScanTicketResponseDTO.builder()
                    .status(ScanTicketResponseDTO.Status.BOOKING_NOT_CONFIRMED)
                    .ticketNumber(ticketNumber)
                    .bookingItemId(item.getId())
                    .build();
            recordRefusal(ticketNumber, item, ScanAttempt.Outcome.BOOKING_NOT_CONFIRMED,
                    scannerOrganizerUuid, scannerUserUuid, scannerEmail, scannerDisplayName, start, eventId);
            return result;
        }

        if (!scannerOwnsEvent(booking, scannerOrganizerUuid)) {
            log.warn("Ticket scan rejected, organizer mismatch ticketNumber={} scannerEmail={} " +
                            "scannerOrganizerUuid={} bookingTenantUserUuid={}",
                    TicketNumbers.forLog(ticketNumber), scannerEmail, scannerOrganizerUuid,
                    booking.getTenantUserUuid());
            ScanTicketResponseDTO result = ScanTicketResponseDTO.builder()
                    .status(ScanTicketResponseDTO.Status.WRONG_ORGANIZER)
                    .ticketNumber(ticketNumber)
                    .bookingItemId(item.getId())
                    .build();
            recordRefusal(ticketNumber, item, ScanAttempt.Outcome.WRONG_ORGANIZER,
                    scannerOrganizerUuid, scannerUserUuid, scannerEmail, scannerDisplayName, start, eventId);
            return result;
        }

        // The event the scanner said they are scanning for, when they said:
        // after the ownership gate (so it reveals nothing to a scanner who may
        // not act on this booking), before the claim.
        if (eventId != null && !eventId.equals(booking.getEventId())) {
            log.info("Ticket scan rejected, ticket is for another event ticketNumber={} scanningFor={} by={}",
                    TicketNumbers.forLog(ticketNumber), eventId, scannerEmail);
            recordRefusal(ticketNumber, item, ScanAttempt.Outcome.WRONG_EVENT,
                    scannerOrganizerUuid, scannerUserUuid, scannerEmail, scannerDisplayName, start, eventId);
            return wrongEvent(ticketNumber, item);
        }

        ScanTicketResponseDTO offDay = eventDayRefusal(item, ticketNumber, scannerOrganizerUuid, scannerUserUuid,
                scannerEmail, scannerDisplayName, start, eventId);
        if (offDay != null) {
            return offDay;
        }

        // Phase 3: the claim and its audit row, in ONE transaction, opened only
        // now that every remote question has been answered.
        return TransactionPhases.inTransaction(writeTx, () -> claimAndAudit(item, ticketNumber,
                scannerOrganizerUuid, scannerUserUuid, scannerEmail, scannerDisplayName, start, eventId));
    }

    /**
     * A scan from the InnBucks super app. The caller is a customer session
     * (the {@code /auth/exchange} token), which carries no organizer scope, so
     * its authority is asked of event-service on every scan: an accepted,
     * enabled scanner membership assigned to {@code eventId}. Nothing about the
     * ticket is read until that answers yes, so a phone that is not on the
     * event's team learns nothing about any ticket.
     *
     * <p>Order: event named → access (503 when it cannot be asked, never a
     * refusal) → ticket found → ticket is for that event and its organizer →
     * booking confirmed → event day → claim. Every row it audits is the
     * scanned-for event's, with the scanner's name from their membership and no
     * email (a super-app session has none to trust). A caller who is not on the
     * team writes no row at all.
     */
    private ScanTicketResponseDTO scanForSuperApp(String ticketNumber, UUID eventId,
                                                  Authentication auth, long start) {
        if (eventId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "eventId is required");
        }
        UUID scannerUserUuid = AuthenticatedCaller.userUuid(auth);
        if (scannerUserUuid == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        ScannerAccessDTO access = superAppAccess(scannerUserUuid, eventId);
        if (!access.isAllowed()) {
            // Logged and counted, NOT written to scan_attempts: any customer
            // can send this, and the event's scan report is the organizer's
            // gate traffic, not a place a stranger can write rows into.
            log.info("Super-app scan rejected, not on the event's team ticketNumber={} eventId={} userUuid={}",
                    TicketNumbers.forLog(ticketNumber), eventId, scannerUserUuid);
            MeterRegistry registry = meterRegistryProvider == null ? null : meterRegistryProvider.getIfAvailable();
            if (registry != null) {
                registry.counter("tickets.scan.super_app_refused").increment();
            }
            return ScanTicketResponseDTO.builder()
                    .status(ScanTicketResponseDTO.Status.NOT_ASSIGNED_TO_EVENT)
                    .ticketNumber(ticketNumber)
                    .build();
        }
        UUID organizerUuid = access.getOrganizerUuid();
        String displayName = access.getDisplayName();

        BookingItem item = TransactionPhases.inTransaction(readTx,
                () -> bookingItemRepository.findByTicketNumberWithBooking(ticketNumber))
                .orElse(null);
        if (item == null) {
            log.info("Super-app scan miss ticketNumber={} eventId={} userUuid={}",
                    TicketNumbers.forLog(ticketNumber), eventId, scannerUserUuid);
            recordRefusal(ticketNumber, null, ScanAttempt.Outcome.TICKET_NOT_FOUND,
                    organizerUuid, scannerUserUuid, null, displayName, start, eventId);
            return ScanTicketResponseDTO.builder()
                    .status(ScanTicketResponseDTO.Status.TICKET_NOT_FOUND)
                    .ticketNumber(ticketNumber)
                    .build();
        }

        // Another event's ticket, or one whose booking is not the organizer's
        // (a stale stamp): the same answer, so a scanner at one gate learns
        // nothing about which other event a ticket is for.
        Booking booking = item.getBooking();
        if (!eventId.equals(booking.getEventId()) || !scannerOwnsEvent(booking, organizerUuid)) {
            log.info("Super-app scan rejected, ticket is for another event ticketNumber={} eventId={} userUuid={}",
                    TicketNumbers.forLog(ticketNumber), eventId, scannerUserUuid);
            recordRefusal(ticketNumber, item, ScanAttempt.Outcome.WRONG_EVENT,
                    organizerUuid, scannerUserUuid, null, displayName, start, eventId);
            return wrongEvent(ticketNumber, item);
        }

        if (booking.getStatus() != Booking.BookingStatus.CONFIRMED) {
            log.info("Super-app scan rejected, booking not confirmed ticketNumber={} status={} userUuid={}",
                    TicketNumbers.forLog(ticketNumber), booking.getStatus(), scannerUserUuid);
            recordRefusal(ticketNumber, item, ScanAttempt.Outcome.BOOKING_NOT_CONFIRMED,
                    organizerUuid, scannerUserUuid, null, displayName, start, eventId);
            return ScanTicketResponseDTO.builder()
                    .status(ScanTicketResponseDTO.Status.BOOKING_NOT_CONFIRMED)
                    .ticketNumber(ticketNumber)
                    .bookingItemId(item.getId())
                    .build();
        }

        ScanTicketResponseDTO offDay = eventDayRefusal(item, ticketNumber, organizerUuid, scannerUserUuid,
                null, displayName, start, eventId);
        if (offDay != null) {
            return offDay;
        }

        return TransactionPhases.inTransaction(writeTx, () -> claimAndAudit(item, ticketNumber,
                organizerUuid, scannerUserUuid, null, displayName, start, eventId));
    }

    /**
     * True for a super-app scanner: a token holding CUSTOMER and not
     * EVENT_ORGANIZER. An organizer stays on the organizer path even if they
     * also shop; a token with no roles at all is never routed here. No other
     * role changes the routing: whatever else a CUSTOMER token carries, its
     * authority to scan is event-service's membership answer and nothing else.
     */
    public static boolean isSuperAppScanner(Authentication auth) {
        if (auth == null) {
            return false;
        }
        boolean customer = false;
        for (GrantedAuthority authority : auth.getAuthorities()) {
            String name = authority.getAuthority();
            if ("ROLE_EVENT_ORGANIZER".equals(name)) {
                return false;
            }
            if ("ROLE_CUSTOMER".equals(name)) {
                customer = true;
            }
        }
        return customer;
    }

    /**
     * event-service's answer to "may this account scan this event": an
     * accepted, enabled scanner membership of the event's organizer, assigned
     * to the event. An answer that cannot be had is a 503, never a refusal and
     * never an allow: the app retries instead of telling a real scanner they
     * are not on the team, and nobody is let through on an outage.
     */
    public ScannerAccessDTO superAppAccess(UUID userUuid, UUID eventId) {
        EventServiceClient client = eventServiceClientProvider == null
                ? null : eventServiceClientProvider.getIfAvailable();
        ScannerAccessDTO access = null;
        if (client != null) {
            try {
                ApiResult<ScannerAccessDTO> response = client.scannerAccess(userUuid, eventId, internalToken);
                access = response == null ? null : response.getData();
            } catch (Exception ex) {
                log.warn("Scanner access check failed eventId={} error={}", eventId, ex.toString());
            }
        }
        if (access == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Could not confirm you can scan for this event. Please try again.");
        }
        return access;
    }

    /**
     * The event-day rule, shared by both scan paths. A ticket redeems only on
     * the market-local calendar days its event actually spans. Called AFTER the
     * authorization gates (so a scanner who may not act on the event cannot
     * probe it for a schedule) and BEFORE the claim, because claimRedemption is
     * the irreversible {@code UPDATE ... WHERE redeemed_at IS NULL} and the
     * repository has no inverse: refusing after it would burn a ticket that is
     * valid tomorrow.
     *
     * @return the WRONG_EVENT_DAY refusal (already audited), or null to go on
     * @throws ResponseStatusException 503 when the event's dates cannot be
     *         established and the fail-open flag is off. Not a verdict about the
     *         ticket, so the scanner retries instead of turning a valid customer
     *         away, and the ticket is not audited as WRONG_EVENT_DAY when we do
     *         not know that it is.
     */
    private ScanTicketResponseDTO eventDayRefusal(BookingItem item, String ticketNumber,
                                                  UUID scannerOrganizerUuid, UUID scannerUserUuid,
                                                  String scannerEmail, String scannerDisplayName,
                                                  long start, UUID scanEventId) {
        if (!eventDayCheckEnabled) {
            return null;
        }
        UUID eventId = item.getBooking().getEventId();
        EventDayRule.Verdict verdict = eventDayVerdict(eventId);
        if (verdict == EventDayRule.Verdict.UNVERIFIABLE && !eventDayCheckFailOpen) {
            log.warn("Ticket scan unverifiable, event window unavailable ticketNumber={} eventId={} by={}",
                    TicketNumbers.forLog(ticketNumber), eventId, scannerEmail);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Could not confirm the event's date. Please try again.");
        }
        if (verdict == EventDayRule.Verdict.OFF_DAY) {
            LocalDate eventDay = eventFirstLocalDay(eventId);
            log.info("Ticket scan rejected, not the event's day ticketNumber={} eventId={} eventDay={} by={}",
                    TicketNumbers.forLog(ticketNumber), eventId, eventDay, scannerEmail);
            recordRefusal(ticketNumber, item, ScanAttempt.Outcome.WRONG_EVENT_DAY,
                    scannerOrganizerUuid, scannerUserUuid, scannerEmail, scannerDisplayName, start, scanEventId);
            return ScanTicketResponseDTO.builder()
                    .status(ScanTicketResponseDTO.Status.WRONG_EVENT_DAY)
                    .ticketNumber(ticketNumber)
                    .bookingItemId(item.getId())
                    .eventDate(eventDay)
                    .build();
        }
        return null;
    }

    private static ScanTicketResponseDTO wrongEvent(String ticketNumber, BookingItem item) {
        return ScanTicketResponseDTO.builder()
                .status(ScanTicketResponseDTO.Status.WRONG_EVENT)
                .ticketNumber(ticketNumber)
                .bookingItemId(item.getId())
                .build();
    }

    /**
     * The irreversible half of a scan: the atomic claim and its audit row,
     * which commit or roll back together. Runs inside the scan's write
     * transaction and makes no remote call.
     */
    private ScanTicketResponseDTO claimAndAudit(BookingItem item,
                                                String ticketNumber,
                                                UUID scannerOrganizerUuid,
                                                UUID scannerUserUuid,
                                                String scannerEmail,
                                                String scannerDisplayName,
                                                long start,
                                                UUID scanEventId) {
        // Atomic claim. UPDATE returns 1 = first scanner wins; 0 = somebody
        // else already redeemed (or this caller already did on a previous
        // try). Re-read the row to surface the original audit fields in the
        // ALREADY_REDEEMED branch — a fresh read in this transaction, so it
        // sees the winner's committed redeemedAt / redeemedByName.
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        int updated = bookingItemRepository.claimRedemption(
                item.getId(), now, scannerUserUuid, scannerDisplayName);
        if (updated == 0) {
            BookingItem reloaded = bookingItemRepository.findByTicketNumberWithBooking(ticketNumber)
                    .orElse(item);
            boolean cancelledMeanwhile = reloaded.getRedeemedAt() == null
                    && (Boolean.FALSE.equals(reloaded.getIsActive())
                        || (reloaded.getBooking() != null
                            && reloaded.getBooking().getStatus() != Booking.BookingStatus.CONFIRMED));
            if (cancelledMeanwhile) {
                // Not redeemed and no longer active, so the claim lost to a
                // cancellation that committed after this scan read the booking
                // (a voided cash sale, an admin reversal): the ticket no longer
                // admits.
                log.info("Ticket scan rejected, booking cancelled during the scan ticketNumber={} scanner={}",
                        TicketNumbers.forLog(ticketNumber), scannerEmail);
                recordAttempt(ticketNumber, reloaded, ScanAttempt.Outcome.BOOKING_NOT_CONFIRMED,
                        scannerOrganizerUuid, scannerUserUuid, scannerEmail, scannerDisplayName, start, scanEventId);
                return ScanTicketResponseDTO.builder()
                        .status(ScanTicketResponseDTO.Status.BOOKING_NOT_CONFIRMED)
                        .ticketNumber(ticketNumber)
                        .bookingItemId(reloaded.getId())
                        .build();
            }
            log.info("Ticket scan rejected, already redeemed ticketNumber={} firstScanAt={} firstScanBy={} retryBy={}",
                    TicketNumbers.forLog(ticketNumber), reloaded.getRedeemedAt(), reloaded.getRedeemedByName(), scannerEmail);
            ScanTicketResponseDTO result = ScanTicketResponseDTO.builder()
                    .status(ScanTicketResponseDTO.Status.ALREADY_REDEEMED)
                    .ticketNumber(ticketNumber)
                    .bookingItemId(reloaded.getId())
                    .redeemedAt(reloaded.getRedeemedAt())
                    .redeemedByName(reloaded.getRedeemedByName())
                    .holderName(reloaded.holderName())
                    .categoryName(reloaded.getCategoryName())
                    .addOns(reloaded.addOnNames())
                    .assignedNumber(reloaded.getAssignedNumber())
                    .numberLabel(reloaded.getNumberLabel())
                    .build();
            recordAttempt(ticketNumber, reloaded, ScanAttempt.Outcome.ALREADY_REDEEMED,
                    scannerOrganizerUuid, scannerUserUuid, scannerEmail, scannerDisplayName, start, scanEventId);
            return result;
        }

        log.info("Ticket scan allowed ticketNumber={} bookingItemId={} scanner={} at={}",
                TicketNumbers.forLog(ticketNumber), item.getId(), scannerEmail, now);
        ScanTicketResponseDTO result = ScanTicketResponseDTO.builder()
                .status(ScanTicketResponseDTO.Status.ALLOWED)
                .ticketNumber(ticketNumber)
                .bookingItemId(item.getId())
                .redeemedAt(now)
                .redeemedByName(scannerDisplayName)
                // Who the ticket was issued to (V22) — the gate can greet or
                // ID-check the holder. Attendee if named, else the purchaser.
                .holderName(item.holderName())
                .categoryName(item.getCategoryName())
                .addOns(item.addOnNames())
                .assignedNumber(item.getAssignedNumber())
                .numberLabel(item.getNumberLabel())
                .build();
        recordAttempt(ticketNumber, item, ScanAttempt.Outcome.ALLOWED,
                scannerOrganizerUuid, scannerUserUuid, scannerEmail, scannerDisplayName, start, scanEventId);
        return result;
    }

    /**
     * A refusal's audit row, in a short write transaction of its own: a
     * refusal decided after a remote check must not have held a transaction
     * across that check just to write one INSERT at the end. Nothing else is
     * written on these paths, so there is nothing for the row to commit or roll
     * back with.
     */
    private void recordRefusal(String ticketNumber,
                               BookingItem item,
                               ScanAttempt.Outcome outcome,
                               UUID scannerOrganizerUuid,
                               UUID scannerUserUuid,
                               String scannerEmail,
                               String scannerDisplayName,
                               long start,
                               UUID scanEventId) {
        TransactionPhases.runInTransaction(writeTx, () -> recordAttempt(ticketNumber, item, outcome,
                scannerOrganizerUuid, scannerUserUuid, scannerEmail, scannerDisplayName, start, scanEventId));
    }

    /**
     * The scan's authorization on the organizer path — the caller is an
     * organizer, and the booking is theirs — for a caller that holds a BOOKING
     * rather than a ticket ({@link GateLookupService}). Returns the refusal, or
     * null when the caller may act on this booking.
     *
     * <p>Composes the same private checks {@link #scan} runs, in the same
     * order, so the gate lookup can never be looser than the scan it feeds:
     * a scanner who could not redeem a booking's tickets cannot list them
     * either. {@code reference} is only for the log lines.
     *
     * @throws ResponseStatusException 403 for a caller without EVENT_ORGANIZER
     *         ({@link #requireOrganizer})
     */
    public ScanTicketResponseDTO.Status authorizationRefusal(Booking booking, String reference) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        requireOrganizer(auth);
        UUID scannerOrganizerUuid = AuthenticatedCaller.organizerUuid(auth);
        String scannerEmail = auth == null ? null : auth.getName();
        if (!scannerOwnsEvent(booking, scannerOrganizerUuid)) {
            log.warn("Gate lookup rejected, organizer mismatch reference={} scannerEmail={} "
                            + "scannerOrganizerUuid={} bookingTenantUserUuid={}",
                    reference, scannerEmail, scannerOrganizerUuid, booking.getTenantUserUuid());
            return ScanTicketResponseDTO.Status.WRONG_ORGANIZER;
        }
        return null;
    }

    /**
     * The organizer path is the event's organizer's own account and nobody
     * else's. A caller that is neither an organizer nor a super-app scanner
     * (routed away before this is asked) is refused with a 403 before anything
     * is read, and writes no {@code scan_attempts} row: it is not a scanner,
     * so there is no gate traffic to record. The controllers' role check makes
     * this unreachable over HTTP; it is here so the service never relies on it
     * alone (the gate-staff TEAM_MEMBER accounts it used to admit are retired).
     */
    static void requireOrganizer(Authentication auth) {
        if (!isOrganizer(auth)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only the event's organizer or its gate scanners can scan tickets.");
        }
    }

    /**
     * Persist one {@code scan_attempts} row capturing the outcome plus the
     * request-scoped fingerprinting bits (correlation id from MDC, client IP
     * / user-agent from the current servlet request, country from MDC). The
     * insert runs inside the caller's transaction — {@link #claimAndAudit}'s,
     * so it rolls back together with the {@code claimRedemption} update on a
     * crash, or {@link #recordRefusal}'s.
     *
     * <p>Wrapped in try/catch and intentionally swallowing: the customer is
     * at the gate. A failure here is logged at WARN and counted via
     * {@code tickets.scan.audit_write{outcome=fail}} so it shows up on the
     * service's metrics dashboard.
     *
     * <p>Note the limit of that protection: only failures raised BEFORE the
     * transaction flushes are caught here. See the class javadoc — the INSERT
     * itself lands at commit, outside this frame.
     *
     * <p>{@code scanEventId} is the event the scanner said they were scanning
     * for. When given, the row is that event's, so a refusal at its gate (a
     * ticket for another event, or one not found at all) shows in that event's
     * scans. Null = the ticket's own event, as before.
     */
    private void recordAttempt(String ticketNumber,
                               BookingItem item,
                               ScanAttempt.Outcome outcome,
                               UUID scannerOrganizerUuid,
                               UUID scannerUserUuid,
                               String scannerEmail,
                               String scannerDisplayName,
                               long start,
                               UUID scanEventId) {
        try {
            HttpServletRequest req = currentRequest();
            String clientIp = req == null ? null : req.getRemoteAddr();
            String userAgent = req == null ? null : req.getHeader("User-Agent");
            String correlationId = MDC.get("correlationId");
            // The CountryMdcConfig filter writes the deployment country into
            // MDC, but if this is called outside a request (rare — only the
            // unauth defence-in-depth path) we fall back to the injected
            // configured value rather than null.
            String country = MDC.get(CountryMdcConfig.MDC_KEY);
            if (country == null || country.isBlank()) {
                country = deploymentCountry;
            }

            ScanAttempt attempt = ScanAttempt.builder()
                    .id(UUID.randomUUID())
                    .attemptedAt(Instant.now())
                    .outcome(outcome)
                    .ticketNumber(ticketNumber)
                    .bookingItemId(item == null ? null : item.getId())
                    .bookingId(item == null || item.getBooking() == null
                            ? null : item.getBooking().getId())
                    .eventId(scanEventId != null ? scanEventId
                            : item == null || item.getBooking() == null
                            ? null : item.getBooking().getEventId())
                    .scannerUserUuid(scannerUserUuid)
                    .scannerEmail(scannerEmail)
                    .scannerDisplayName(scannerDisplayName)
                    .scannerOrganizerUuid(scannerOrganizerUuid)
                    .correlationId(correlationId)
                    .clientIp(clientIp)
                    .userAgent(userAgent)
                    // TODO wire JWT did claim — JwtAuthDetails has no deviceId
                    // field yet. Left null until the device-binding slice lands.
                    .deviceId(null)
                    .latencyMs((int) Math.min(Integer.MAX_VALUE,
                            System.currentTimeMillis() - start))
                    .country(country)
                    .build();
            scanAttemptRepository.save(attempt);
        } catch (Exception e) {
            log.warn("Failed to record scan_attempts audit row ticketNumber={} outcome={} scanner={} cause={}",
                    TicketNumbers.forLog(ticketNumber), outcome, scannerEmail, TicketNumbers.redactIn(e.toString()));
            MeterRegistry registry = meterRegistryProvider == null
                    ? null : meterRegistryProvider.getIfAvailable();
            if (registry != null) {
                registry.counter("tickets.scan.audit_write", "outcome", "fail").increment();
            }
        }
    }

    /** Returns the current servlet request, or null when the call is not
     *  request-scoped (e.g. an internal test exercising the service directly). */
    private static HttpServletRequest currentRequest() {
        try {
            var attrs = RequestContextHolder.getRequestAttributes();
            if (attrs instanceof ServletRequestAttributes sra) {
                return sra.getRequest();
            }
        } catch (IllegalStateException ignored) {
            // No bound request — caller is outside the servlet container.
        }
        return null;
    }

    /**
     * Authorization check: the booking's {@code tenant_user_uuid} (the owning
     * organizer, mirrored from the event) must equal the scanner's
     * {@code organizerUuid} JWT claim. UUID-keyed and immune to email churn.
     * A booking whose {@code tenant_user_uuid} is null (event-service was
     * unreachable at create) fails closed — better to refuse the scan than to
     * let a ticket nobody can attribute through the gate.
     */
    private boolean scannerOwnsEvent(Booking booking, UUID scannerOrganizerUuid) {
        UUID bookingOrganizerUuid = booking.getTenantUserUuid();
        // No null-guard on scannerOrganizerUuid: equals(null) is already false,
        // so a null scanner uuid correctly fails the check.
        return bookingOrganizerUuid != null
                && bookingOrganizerUuid.equals(scannerOrganizerUuid);
    }

    private static boolean isOrganizer(Authentication auth) {
        if (auth == null) return false;
        return auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_EVENT_ORGANIZER".equals(a.getAuthority()));
    }

    /**
     * Today's verdict for this event, reading the window through a 60s cache.
     *
     * <p>Never throws: any failure to resolve the window — no client bean, a
     * null body from the Feign fallback, a thrown exception — collapses to
     * UNVERIFIABLE, and the CALLER decides what that means, so the fail-open
     * flag is the single place the rule's outage behaviour is decided.
     */
    private EventDayRule.Verdict eventDayVerdict(UUID eventId) {
        MarketTimeZone market = marketTimeZoneProvider == null
                ? null : marketTimeZoneProvider.getIfAvailable();
        if (eventId == null || market == null) {
            return EventDayRule.Verdict.UNVERIFIABLE;
        }
        CachedWindow window = eventWindow(eventId);
        if (window == null) {
            return EventDayRule.Verdict.UNVERIFIABLE;
        }
        return EventDayRule.classify(window.startUtc(), window.endUtc(), Instant.now(), market);
    }

    /** The event's first market-local day, for the refusal payload. Null when unknown. */
    private LocalDate eventFirstLocalDay(UUID eventId) {
        MarketTimeZone market = marketTimeZoneProvider == null
                ? null : marketTimeZoneProvider.getIfAvailable();
        CachedWindow window = eventId == null ? null : eventWindow(eventId);
        if (market == null || window == null) {
            return null;
        }
        return EventDayRule.firstLocalDay(window.startUtc(), market);
    }

    /**
     * The event's stored UTC start/end, cached for {@link #EVENT_WINDOW_TTL_MILLIS}.
     * Null means "could not resolve" — never cached, so one blip does not
     * become a minute of refusals.
     */
    private CachedWindow eventWindow(UUID eventId) {
        CachedWindow cached = eventWindowCache.get(eventId);
        if (cached != null && System.currentTimeMillis() - cached.cachedAtMillis() < EVENT_WINDOW_TTL_MILLIS) {
            return cached;
        }
        EventServiceClient client = eventServiceClientProvider == null
                ? null : eventServiceClientProvider.getIfAvailable();
        if (client == null) {
            return null;
        }
        try {
            // The INTERNAL lookup: the public GET /events/{id} strips fields
            // for anonymous callers and a server-side Feign call is anonymous.
            // It also still answers for unpublished events, so a ticket for an
            // event pulled from sale is judged on its dates rather than
            // becoming unverifiable.
            ApiResult<EventLookupDTO> response = client.getEventInternal(eventId, internalToken);
            EventLookupDTO event = response == null ? null : response.getData();
            if (event == null || event.getStartDateTime() == null) {
                return null;
            }
            CachedWindow fresh = new CachedWindow(
                    event.getStartDateTime(), event.getEndDateTime(), System.currentTimeMillis());
            eventWindowCache.put(eventId, fresh);
            return fresh;
        } catch (Exception ex) {
            // Includes the open-circuit case. Deliberately swallowed to
            // UNVERIFIABLE rather than propagated, so the caller's fail-open
            // flag is what decides, not an exception type.
            log.warn("Event window lookup failed eventId={} error={}", eventId, ex.toString());
            return null;
        }
    }
}
