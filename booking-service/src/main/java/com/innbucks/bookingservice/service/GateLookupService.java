package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.dto.GateLookupResponseDTO;
import com.innbucks.bookingservice.dto.ScanTicketResponseDTO;
import com.innbucks.bookingservice.dto.ScannerAccessDTO;
import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.entity.BookingItem;
import com.innbucks.bookingservice.repository.BookingRepository;
import com.innbucks.bookingservice.util.MsisdnMasking;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import com.innbucks.bookingservice.security.AuthenticatedCaller;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Finds a booking by its confirmation number at the gate — the fallback for a
 * customer who has no WhatsApp and so never received the QR, but did get the
 * confirmation number by SMS.
 *
 * <p><b>Read-only by design.</b> It lists the booking's tickets and admits
 * nobody. The gate app admits a holder by sending the chosen ticket's
 * {@code ticketNumber} to {@code POST /tickets/scan}, so the single-shot claim,
 * the event-day rule and the {@code scan_attempts} audit row are exactly what a
 * QR scan gets. A booking usually holds several tickets for different people,
 * so "admit the booking" would be the wrong unit anyway: the operator picks the
 * person in front of them.
 *
 * <p><b>Authorization is the scan's own</b>
 * ({@link TicketScanService#authorizationRefusal}): a scanner who could not
 * redeem this booking's tickets cannot list them. Every refusal carries only
 * the echoed confirmation number.
 *
 * <p><b>A confirmation number is a weaker credential than the QR.</b> It is
 * short, sent in plain SMS and easy to share, so the response carries the
 * holder names and the last four digits of the purchaser's phone for the
 * operator to check against the person at the gate. Lookups are logged and
 * counted ({@code tickets.gate_lookup{outcome}}) but not written to
 * {@code scan_attempts}: a lookup redeems nothing, and counting it there would
 * inflate every scan report. The admission itself is audited by the scan.
 *
 * <p><b>No transaction is open during the authorization check.</b> The booking
 * and its tickets are read in a short read-only transaction (one fetch-join, so
 * the detached copy carries every ticket); the team-member assignment question
 * to user-service is asked after it has closed. It used to run inside a
 * method-level read-only transaction, holding a pooled connection for as long
 * as user-service took to answer. Nothing is written here, so there is no
 * lock or idempotency property for the split to weaken.
 */
@Service
@Slf4j
public class GateLookupService {

    private final BookingRepository bookingRepository;
    private final TicketScanService ticketScanService;
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;

    public GateLookupService(BookingRepository bookingRepository,
                             TicketScanService ticketScanService,
                             ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.bookingRepository = bookingRepository;
        this.ticketScanService = ticketScanService;
        this.meterRegistryProvider = meterRegistryProvider;
    }

    /**
     * The read phase's template. A setter so the plain-{@code new} unit tests
     * keep their construction; with none set, the read runs inline.
     */
    private TransactionTemplate readTx;

    @Autowired
    void setTransactionManager(PlatformTransactionManager transactionManager) {
        this.readTx = TransactionPhases.readOnly(transactionManager);
    }

    /** Deliberately NOT {@code @Transactional} — see the class javadoc. */
    public GateLookupResponseDTO lookup(String rawConfirmationNumber) {
        return lookup(rawConfirmationNumber, null);
    }

    /**
     * @param eventId the event the gate is scanning for. Required from a
     *                super-app scanner, optional for the scanner app; when
     *                given, another event's booking is {@code WRONG_EVENT} and
     *                none of its tickets are listed.
     */
    public GateLookupResponseDTO lookup(String rawConfirmationNumber, UUID eventId) {
        String confirmationNumber = normalise(rawConfirmationNumber);
        if (confirmationNumber.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "confirmationNumber is required");
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (TicketScanService.isSuperAppScanner(auth)) {
            return lookupForSuperApp(confirmationNumber, eventId, auth);
        }
        String scanner = auth == null ? null : auth.getName();

        // Items fetched with the booking: the FOUND answer lists every ticket.
        Booking booking = TransactionPhases.inTransaction(readTx,
                () -> bookingRepository.findByConfirmationNumberWithItems(confirmationNumber))
                .orElse(null);
        if (booking == null) {
            log.info("Gate lookup miss confirmationNumber={} scanner={}", confirmationNumber, scanner);
            return refusal(GateLookupResponseDTO.Status.BOOKING_NOT_FOUND, confirmationNumber);
        }
        if (booking.getStatus() != Booking.BookingStatus.CONFIRMED) {
            log.info("Gate lookup rejected, booking not confirmed confirmationNumber={} status={} scanner={}",
                    confirmationNumber, booking.getStatus(), scanner);
            return refusal(GateLookupResponseDTO.Status.BOOKING_NOT_CONFIRMED, confirmationNumber);
        }
        // May ask user-service — after the read transaction has closed.
        ScanTicketResponseDTO.Status refused =
                ticketScanService.authorizationRefusal(booking, confirmationNumber);
        if (refused != null) {
            return refusal(GateLookupResponseDTO.Status.valueOf(refused.name()), confirmationNumber);
        }
        if (eventId != null && !eventId.equals(booking.getEventId())) {
            log.info("Gate lookup rejected, booking is for another event confirmationNumber={} scanningFor={} scanner={}",
                    confirmationNumber, eventId, scanner);
            return refusal(GateLookupResponseDTO.Status.WRONG_EVENT, confirmationNumber);
        }
        return found(booking, confirmationNumber, scanner);
    }

    /**
     * The super-app gate: the caller's access to {@code eventId} is asked of
     * event-service BEFORE the booking is read (503 when it cannot be asked),
     * so a phone that is not on the event's team learns nothing about any
     * booking, not even whether a confirmation number exists. Then another
     * event's or another organizer's booking is {@code WRONG_EVENT}, the same
     * answer for both.
     */
    private GateLookupResponseDTO lookupForSuperApp(String confirmationNumber, UUID eventId, Authentication auth) {
        if (eventId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "eventId is required");
        }
        UUID userUuid = AuthenticatedCaller.userUuid(auth);
        if (userUuid == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        String scanner = "user:" + userUuid;
        ScannerAccessDTO access = ticketScanService.superAppAccess(userUuid, eventId);
        if (!access.isAllowed()) {
            log.info("Gate lookup rejected, not on the event's team eventId={} scanner={}", eventId, scanner);
            return refusal(GateLookupResponseDTO.Status.NOT_ASSIGNED_TO_EVENT, confirmationNumber);
        }
        Booking booking = TransactionPhases.inTransaction(readTx,
                () -> bookingRepository.findByConfirmationNumberWithItems(confirmationNumber))
                .orElse(null);
        if (booking == null) {
            log.info("Gate lookup miss confirmationNumber={} scanner={}", confirmationNumber, scanner);
            return refusal(GateLookupResponseDTO.Status.BOOKING_NOT_FOUND, confirmationNumber);
        }
        if (!eventId.equals(booking.getEventId()) || booking.getTenantUserUuid() == null
                || !booking.getTenantUserUuid().equals(access.getOrganizerUuid())) {
            log.info("Gate lookup rejected, booking is for another event confirmationNumber={} eventId={} scanner={}",
                    confirmationNumber, eventId, scanner);
            return refusal(GateLookupResponseDTO.Status.WRONG_EVENT, confirmationNumber);
        }
        if (booking.getStatus() != Booking.BookingStatus.CONFIRMED) {
            log.info("Gate lookup rejected, booking not confirmed confirmationNumber={} status={} scanner={}",
                    confirmationNumber, booking.getStatus(), scanner);
            return refusal(GateLookupResponseDTO.Status.BOOKING_NOT_CONFIRMED, confirmationNumber);
        }
        return found(booking, confirmationNumber, scanner);
    }

    private GateLookupResponseDTO found(Booking booking, String confirmationNumber, String scanner) {
        List<BookingItem> items = booking.getItems() == null ? List.of() : booking.getItems();
        List<GateLookupResponseDTO.Ticket> tickets = items.stream()
                .sorted(Comparator.comparing(BookingItem::getTicketNumber))
                .map(GateLookupService::toTicket)
                .toList();
        String phone = booking.getPhoneNumber();
        log.info("Gate lookup found confirmationNumber={} tickets={} scanner={}",
                confirmationNumber, tickets.size(), scanner);
        count(GateLookupResponseDTO.Status.FOUND);
        return GateLookupResponseDTO.builder()
                .status(GateLookupResponseDTO.Status.FOUND)
                .confirmationNumber(confirmationNumber)
                .customerName(booking.getCustomerName())
                .customerPhoneLast4(phone == null || phone.isBlank() ? null : MsisdnMasking.mask(phone))
                .tickets(tickets)
                .build();
    }

    /** Confirmation numbers are generated upper-case; people read and type them however they like. */
    static String normalise(String raw) {
        return raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
    }

    private static GateLookupResponseDTO.Ticket toTicket(BookingItem item) {
        boolean redeemed = item.getRedeemedAt() != null;
        return GateLookupResponseDTO.Ticket.builder()
                .ticketNumber(item.getTicketNumber())
                .bookingItemId(item.getId())
                .categoryName(item.getCategoryName())
                .holderName(item.holderName())
                .redeemed(redeemed)
                .redeemedAt(item.getRedeemedAt())
                .redeemedByName(redeemed ? item.getRedeemedByName() : null)
                .build();
    }

    private GateLookupResponseDTO refusal(GateLookupResponseDTO.Status status, String confirmationNumber) {
        count(status);
        return GateLookupResponseDTO.builder()
                .status(status)
                .confirmationNumber(confirmationNumber)
                .build();
    }

    private void count(GateLookupResponseDTO.Status status) {
        MeterRegistry registry = meterRegistryProvider == null ? null : meterRegistryProvider.getIfAvailable();
        if (registry != null) {
            registry.counter("tickets.gate_lookup", "outcome", status.name()).increment();
        }
    }
}
