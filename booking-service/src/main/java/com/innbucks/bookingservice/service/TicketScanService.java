package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.client.UserServiceClient;
import com.innbucks.bookingservice.config.CountryMdcConfig;
import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.ScanAccessDTO;
import com.innbucks.bookingservice.dto.ScanTicketResponseDTO;
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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
 * Single-shot ticket redemption for the scanner-app flow used by an
 * EVENT_ORGANIZER or one of their TEAM_MEMBERs at the gate.
 *
 * <p>Concurrency model: the claim is an atomic UPDATE WHERE redeemed_at
 * IS NULL — so two scanners pointing at the same QR can't both succeed.
 * The first call to {@link BookingItemRepository#claimRedemption} updates
 * 1 row; every subsequent call updates 0 rows and we re-load the row to
 * return the original {@code redeemedByName} + {@code redeemedAt} (the
 * "scanned by Tariro at 19:42" toast).
 *
 * <p>Authorization: the scanner's {@code organizerUuid} JWT claim must
 * equal the booking's {@code tenant_user_uuid} (which mirrors the
 * event's owning organizer).
 *
 * <p><b>Audit invariant:</b> every scan attempt, regardless of outcome,
 * writes exactly one {@code scan_attempts} row. The audit insert shares
 * the same {@code @Transactional} boundary as the {@code claimRedemption}
 * UPDATE so a crash mid-write rolls both back — and, being in that same
 * committed transaction, the row is queryable by the reporting endpoints the
 * moment the scan response is returned. There is no asynchronous hand-off and
 * no propagation delay to wait out.
 *
 * <p><b>What the audit try/catch does and does not buy.</b> It catches what
 * fails while BUILDING the row (a missing request context, an MDC lookup) and
 * whatever the persistence call itself throws. It does NOT make an audit write
 * unable to fail the scan: JPA defers the INSERT to flush-at-commit, which
 * happens after this method has returned, so a constraint violation surfaces
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
    private final UserServiceClient userServiceClient;
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
                             UserServiceClient userServiceClient,
                             ScanAttemptRepository scanAttemptRepository,
                             ObjectProvider<MeterRegistry> meterRegistryProvider,
                             @Value("${innbucks.country:ZW}") String deploymentCountry,
                             ObjectProvider<EventServiceClient> eventServiceClientProvider,
                             ObjectProvider<MarketTimeZone> marketTimeZoneProvider) {
        this.bookingItemRepository = bookingItemRepository;
        this.userServiceClient = userServiceClient;
        this.scanAttemptRepository = scanAttemptRepository;
        this.meterRegistryProvider = meterRegistryProvider;
        this.deploymentCountry = deploymentCountry;
        this.eventServiceClientProvider = eventServiceClientProvider;
        this.marketTimeZoneProvider = marketTimeZoneProvider;
    }

    /** Shared S2S secret for the user-service assignment-check call. */
    @Value("${innbucks.internal-api-token:}")
    private String internalToken;

    /**
     * What to do when user-service can't be reached to resolve a team
     * member's per-event assignment.
     *
     * <p><b>Fail CLOSED by default</b> (false): deny the scan
     * (NOT_ASSIGNED_TO_EVENT) until user-service is back. A restricted team
     * member must NEVER be silently widened to the organizer's whole event
     * set just because the assignment system of record is down — that would
     * lapse the deny-by-default invariant the per-event restriction exists to
     * enforce, and it is asymmetric with the LIST path
     * ({@code UserUuidLookupGateway.assignedEventIdsFor}), which already
     * fails closed (empty list) on an outage.
     *
     * <p>true = break-glass ONLY: fall back to the organizer-wide access
     * already verified above so the gate keeps moving during an outage. This
     * re-opens the gap above, so it must be a deliberate, time-boxed operator
     * decision — never the steady-state default.
     */
    @Value("${innbucks.scan.assignment-check.fail-open:false}")
    private boolean assignmentCheckFailOpen;

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

    @Transactional
    public ScanTicketResponseDTO scan(String ticketNumber, String scannerDisplayName) {
        long start = System.currentTimeMillis();

        if (ticketNumber == null || ticketNumber.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ticketNumber is required");
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
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

        BookingItem item = bookingItemRepository.findByTicketNumberWithBooking(ticketNumber)
                .orElse(null);
        if (item == null) {
            log.info("Ticket scan miss ticketNumber={} scanner={}", ticketNumber, scannerEmail);
            ScanTicketResponseDTO result = ScanTicketResponseDTO.builder()
                    .status(ScanTicketResponseDTO.Status.TICKET_NOT_FOUND)
                    .ticketNumber(ticketNumber)
                    .build();
            recordAttempt(ticketNumber, null, ScanAttempt.Outcome.TICKET_NOT_FOUND,
                    scannerOrganizerUuid, scannerUserUuid, scannerEmail, scannerDisplayName, start);
            return result;
        }

        Booking booking = item.getBooking();
        if (booking.getStatus() != Booking.BookingStatus.CONFIRMED) {
            log.info("Ticket scan rejected, booking not confirmed ticketNumber={} status={} scanner={}",
                    ticketNumber, booking.getStatus(), scannerEmail);
            ScanTicketResponseDTO result = ScanTicketResponseDTO.builder()
                    .status(ScanTicketResponseDTO.Status.BOOKING_NOT_CONFIRMED)
                    .ticketNumber(ticketNumber)
                    .bookingItemId(item.getId())
                    .build();
            recordAttempt(ticketNumber, item, ScanAttempt.Outcome.BOOKING_NOT_CONFIRMED,
                    scannerOrganizerUuid, scannerUserUuid, scannerEmail, scannerDisplayName, start);
            return result;
        }

        if (!scannerOwnsEvent(booking, scannerOrganizerUuid)) {
            log.warn("Ticket scan rejected, organizer mismatch ticketNumber={} scannerEmail={} " +
                            "scannerOrganizerUuid={} bookingTenantUserUuid={}",
                    ticketNumber, scannerEmail, scannerOrganizerUuid,
                    booking.getTenantUserUuid());
            ScanTicketResponseDTO result = ScanTicketResponseDTO.builder()
                    .status(ScanTicketResponseDTO.Status.WRONG_ORGANIZER)
                    .ticketNumber(ticketNumber)
                    .bookingItemId(item.getId())
                    .build();
            recordAttempt(ticketNumber, item, ScanAttempt.Outcome.WRONG_ORGANIZER,
                    scannerOrganizerUuid, scannerUserUuid, scannerEmail, scannerDisplayName, start);
            return result;
        }

        // Per-event restriction. Organizers are never restricted (they own
        // every event); only TEAM_MEMBERs can be narrowed to assigned events.
        // user-service is the assignment system of record and encodes the
        // "no assignments = organizer-wide" rule in the allowed flag.
        if (!isOrganizer(auth) && scannerUserUuid != null
                && !assignmentAllowsScan(scannerUserUuid, booking.getEventId(), ticketNumber, scannerEmail)) {
            ScanTicketResponseDTO result = ScanTicketResponseDTO.builder()
                    .status(ScanTicketResponseDTO.Status.NOT_ASSIGNED_TO_EVENT)
                    .ticketNumber(ticketNumber)
                    .bookingItemId(item.getId())
                    .build();
            recordAttempt(ticketNumber, item, ScanAttempt.Outcome.NOT_ASSIGNED_TO_EVENT,
                    scannerOrganizerUuid, scannerUserUuid, scannerEmail, scannerDisplayName, start);
            return result;
        }

        // Event-day rule. A ticket redeems only on the market-local calendar
        // days its event actually spans. Placed HERE deliberately:
        //
        //  - AFTER both authorization gates, so a scanner who does not own the
        //    event cannot probe it for a schedule; and
        //  - BEFORE the claim below, because claimRedemption is the
        //    irreversible `UPDATE ... WHERE redeemed_at IS NULL` and the
        //    repository has no inverse. Refusing after it would permanently
        //    burn a ticket that is perfectly valid tomorrow.
        if (eventDayCheckEnabled) {
            EventDayRule.Verdict verdict = eventDayVerdict(booking.getEventId());
            if (verdict == EventDayRule.Verdict.UNVERIFIABLE && !eventDayCheckFailOpen) {
                // Not a verdict about the ticket — the server could not decide.
                // 503 rather than a 200-carried refusal so the scanner app
                // retries instead of turning a valid customer away, and so the
                // ticket is not audited as WRONG_EVENT_DAY when we do not know
                // that it is.
                log.warn("Ticket scan unverifiable, event window unavailable ticketNumber={} eventId={} by={}",
                        ticketNumber, booking.getEventId(), scannerEmail);
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Could not confirm the event's date. Please try again.");
            }
            if (verdict == EventDayRule.Verdict.OFF_DAY) {
                LocalDate eventDay = eventFirstLocalDay(booking.getEventId());
                log.info("Ticket scan rejected, not the event's day ticketNumber={} eventId={} eventDay={} by={}",
                        ticketNumber, booking.getEventId(), eventDay, scannerEmail);
                ScanTicketResponseDTO result = ScanTicketResponseDTO.builder()
                        .status(ScanTicketResponseDTO.Status.WRONG_EVENT_DAY)
                        .ticketNumber(ticketNumber)
                        .bookingItemId(item.getId())
                        .eventDate(eventDay)
                        .build();
                recordAttempt(ticketNumber, item, ScanAttempt.Outcome.WRONG_EVENT_DAY,
                        scannerOrganizerUuid, scannerUserUuid, scannerEmail, scannerDisplayName, start);
                return result;
            }
        }

        // Atomic claim. UPDATE returns 1 = first scanner wins; 0 = somebody
        // else already redeemed (or this caller already did on a previous
        // try). Re-read the row to surface the original audit fields in the
        // ALREADY_REDEEMED branch.
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        int updated = bookingItemRepository.claimRedemption(
                item.getId(), now, scannerUserUuid, scannerDisplayName);
        if (updated == 0) {
            BookingItem reloaded = bookingItemRepository.findByTicketNumberWithBooking(ticketNumber)
                    .orElse(item);
            log.info("Ticket scan rejected, already redeemed ticketNumber={} firstScanAt={} firstScanBy={} retryBy={}",
                    ticketNumber, reloaded.getRedeemedAt(), reloaded.getRedeemedByName(), scannerEmail);
            ScanTicketResponseDTO result = ScanTicketResponseDTO.builder()
                    .status(ScanTicketResponseDTO.Status.ALREADY_REDEEMED)
                    .ticketNumber(ticketNumber)
                    .bookingItemId(reloaded.getId())
                    .redeemedAt(reloaded.getRedeemedAt())
                    .redeemedByName(reloaded.getRedeemedByName())
                    .holderName(reloaded.holderName())
                    .build();
            recordAttempt(ticketNumber, reloaded, ScanAttempt.Outcome.ALREADY_REDEEMED,
                    scannerOrganizerUuid, scannerUserUuid, scannerEmail, scannerDisplayName, start);
            return result;
        }

        log.info("Ticket scan allowed ticketNumber={} bookingItemId={} scanner={} at={}",
                ticketNumber, item.getId(), scannerEmail, now);
        ScanTicketResponseDTO result = ScanTicketResponseDTO.builder()
                .status(ScanTicketResponseDTO.Status.ALLOWED)
                .ticketNumber(ticketNumber)
                .bookingItemId(item.getId())
                .redeemedAt(now)
                .redeemedByName(scannerDisplayName)
                // Who the ticket was issued to (V22) — the gate can greet or
                // ID-check the holder. Attendee if named, else the purchaser.
                .holderName(item.holderName())
                .build();
        recordAttempt(ticketNumber, item, ScanAttempt.Outcome.ALLOWED,
                scannerOrganizerUuid, scannerUserUuid, scannerEmail, scannerDisplayName, start);
        return result;
    }

    /**
     * Persist one {@code scan_attempts} row capturing the outcome plus the
     * request-scoped fingerprinting bits (correlation id from MDC, client IP
     * / user-agent from the current servlet request, country from MDC). The
     * insert runs inside the caller's {@code @Transactional} boundary so it
     * rolls back together with the {@code claimRedemption} update on a crash.
     *
     * <p>Wrapped in try/catch and intentionally swallowing: the customer is
     * at the gate. A failure here is logged at WARN and counted via
     * {@code tickets.scan.audit_write{outcome=fail}} so it shows up on the
     * service's metrics dashboard.
     *
     * <p>Note the limit of that protection: only failures raised BEFORE the
     * transaction flushes are caught here. See the class javadoc — the INSERT
     * itself lands at commit, outside this frame.
     */
    private void recordAttempt(String ticketNumber,
                               BookingItem item,
                               ScanAttempt.Outcome outcome,
                               UUID scannerOrganizerUuid,
                               UUID scannerUserUuid,
                               String scannerEmail,
                               String scannerDisplayName,
                               long start) {
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
                    .eventId(item == null || item.getBooking() == null
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
                    ticketNumber, outcome, scannerEmail, e.toString());
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

    private boolean isOrganizer(Authentication auth) {
        if (auth == null) return false;
        return auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_EVENT_ORGANIZER".equals(a.getAuthority()));
    }

    /**
     * Asks user-service whether this team member may scan the given event.
     * Returns the configured {@link #assignmentCheckFailOpen} value when
     * user-service is unreachable (Feign fallback yields null data) so the
     * fail-open/closed policy lives in exactly one place.
     */
    private boolean assignmentAllowsScan(UUID scannerUserUuid, UUID eventId,
                                         String ticketNumber, String scannerEmail) {
        ApiResult<ScanAccessDTO> res = userServiceClient.canScanEvent(scannerUserUuid, eventId, internalToken);
        ScanAccessDTO data = res == null ? null : res.getData();
        if (data != null) {
            return data.isAllowed();
        }
        log.warn("Assignment check unavailable, applying failOpen={} ticketNumber={} scanner={} eventId={}",
                assignmentCheckFailOpen, ticketNumber, scannerEmail, eventId);
        return assignmentCheckFailOpen;
    }

    /**
     * Today's verdict for this event, reading the window through a 60s cache.
     *
     * <p>Never throws: any failure to resolve the window — no client bean, a
     * null body from the Feign fallback, a thrown exception — collapses to
     * UNVERIFIABLE, and the CALLER decides what that means. Keeping the policy
     * at the call site rather than here is the same shape
     * {@code assignmentAllowsScan} uses, so the fail-open flag is the single
     * place either rule's outage behaviour is decided.
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
