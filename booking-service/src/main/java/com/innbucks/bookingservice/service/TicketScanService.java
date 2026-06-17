package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.client.UserServiceClient;
import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.ScanAccessDTO;
import com.innbucks.bookingservice.dto.ScanTicketResponseDTO;
import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.entity.BookingItem;
import com.innbucks.bookingservice.repository.BookingItemRepository;
import com.innbucks.bookingservice.security.AuthenticatedCaller;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

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
 * event's owning organizer). Legacy bookings without a uuid fall back
 * to the email-based tenantId match.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TicketScanService {

    private final BookingItemRepository bookingItemRepository;
    private final UserServiceClient userServiceClient;

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

    @Transactional
    public ScanTicketResponseDTO scan(String ticketNumber, String scannerDisplayName) {
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
            // wired-wrong test.
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }

        BookingItem item = bookingItemRepository.findByTicketNumberWithBooking(ticketNumber)
                .orElse(null);
        if (item == null) {
            log.info("Ticket scan miss ticketNumber={} scanner={}", ticketNumber, scannerEmail);
            return ScanTicketResponseDTO.builder()
                    .status(ScanTicketResponseDTO.Status.TICKET_NOT_FOUND)
                    .ticketNumber(ticketNumber)
                    .build();
        }

        Booking booking = item.getBooking();
        if (booking.getStatus() != Booking.BookingStatus.CONFIRMED) {
            log.info("Ticket scan rejected, booking not confirmed ticketNumber={} status={} scanner={}",
                    ticketNumber, booking.getStatus(), scannerEmail);
            return ScanTicketResponseDTO.builder()
                    .status(ScanTicketResponseDTO.Status.BOOKING_NOT_CONFIRMED)
                    .ticketNumber(ticketNumber)
                    .bookingItemId(item.getId())
                    .build();
        }

        if (!scannerOwnsEvent(booking, scannerOrganizerUuid, scannerEmail)) {
            log.warn("Ticket scan rejected, organizer mismatch ticketNumber={} scannerEmail={} " +
                            "scannerOrganizerUuid={} bookingTenantUserUuid={} bookingTenantId={}",
                    ticketNumber, scannerEmail, scannerOrganizerUuid,
                    booking.getTenantUserUuid(), booking.getTenantId());
            return ScanTicketResponseDTO.builder()
                    .status(ScanTicketResponseDTO.Status.WRONG_ORGANIZER)
                    .ticketNumber(ticketNumber)
                    .bookingItemId(item.getId())
                    .build();
        }

        // Per-event restriction. Organizers are never restricted (they own
        // every event); only TEAM_MEMBERs can be narrowed to assigned events.
        // user-service is the assignment system of record and encodes the
        // "no assignments = organizer-wide" rule in the allowed flag.
        if (!isOrganizer(auth) && scannerUserUuid != null
                && !assignmentAllowsScan(scannerUserUuid, booking.getEventId(), ticketNumber, scannerEmail)) {
            return ScanTicketResponseDTO.builder()
                    .status(ScanTicketResponseDTO.Status.NOT_ASSIGNED_TO_EVENT)
                    .ticketNumber(ticketNumber)
                    .bookingItemId(item.getId())
                    .build();
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
            return ScanTicketResponseDTO.builder()
                    .status(ScanTicketResponseDTO.Status.ALREADY_REDEEMED)
                    .ticketNumber(ticketNumber)
                    .bookingItemId(reloaded.getId())
                    .redeemedAt(reloaded.getRedeemedAt())
                    .redeemedByName(reloaded.getRedeemedByName())
                    .build();
        }

        log.info("Ticket scan allowed ticketNumber={} bookingItemId={} scanner={} at={}",
                ticketNumber, item.getId(), scannerEmail, now);
        return ScanTicketResponseDTO.builder()
                .status(ScanTicketResponseDTO.Status.ALLOWED)
                .ticketNumber(ticketNumber)
                .bookingItemId(item.getId())
                .redeemedAt(now)
                .redeemedByName(scannerDisplayName)
                .build();
    }

    /**
     * Authorization check. Prefers the UUID-keyed comparison (immune to
     * email churn); falls back to the legacy tenantId email match for
     * bookings created before V13 (their {@code tenant_user_uuid} is
     * null). The legacy fallback only helps EVENT_ORGANIZERs scanning
     * their own events — a TEAM_MEMBER's JWT subject is their own email,
     * not the organizer's, so a legacy booking can only be scanned by
     * the organizer themself until V13 has been backfilled.
     */
    private boolean scannerOwnsEvent(Booking booking, UUID scannerOrganizerUuid, String scannerEmail) {
        UUID bookingOrganizerUuid = booking.getTenantUserUuid();
        if (bookingOrganizerUuid != null && scannerOrganizerUuid != null) {
            return bookingOrganizerUuid.equals(scannerOrganizerUuid);
        }
        // Legacy: no uuid stored, fall back to "organizer email == booking
        // tenantId". A TEAM_MEMBER's email never matches their organizer's
        // tenantId, so they're effectively blocked from scanning legacy
        // bookings — acceptable while the backfill catches up.
        String bookingTenantId = booking.getTenantId();
        return bookingTenantId != null && bookingTenantId.equalsIgnoreCase(scannerEmail);
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
}
