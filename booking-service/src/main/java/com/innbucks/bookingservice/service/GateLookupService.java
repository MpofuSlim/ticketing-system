package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.dto.GateLookupResponseDTO;
import com.innbucks.bookingservice.dto.ScanTicketResponseDTO;
import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.entity.BookingItem;
import com.innbucks.bookingservice.repository.BookingRepository;
import com.innbucks.bookingservice.util.MsisdnMasking;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

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

    @Transactional(readOnly = true)
    public GateLookupResponseDTO lookup(String rawConfirmationNumber) {
        String confirmationNumber = normalise(rawConfirmationNumber);
        if (confirmationNumber.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "confirmationNumber is required");
        }
        String scanner = SecurityContextHolder.getContext().getAuthentication() == null
                ? null : SecurityContextHolder.getContext().getAuthentication().getName();

        Booking booking = bookingRepository.findByConfirmationNumber(confirmationNumber).orElse(null);
        if (booking == null) {
            log.info("Gate lookup miss confirmationNumber={} scanner={}", confirmationNumber, scanner);
            return refusal(GateLookupResponseDTO.Status.BOOKING_NOT_FOUND, confirmationNumber);
        }
        if (booking.getStatus() != Booking.BookingStatus.CONFIRMED) {
            log.info("Gate lookup rejected, booking not confirmed confirmationNumber={} status={} scanner={}",
                    confirmationNumber, booking.getStatus(), scanner);
            return refusal(GateLookupResponseDTO.Status.BOOKING_NOT_CONFIRMED, confirmationNumber);
        }
        ScanTicketResponseDTO.Status refused =
                ticketScanService.authorizationRefusal(booking, confirmationNumber);
        if (refused != null) {
            return refusal(GateLookupResponseDTO.Status.valueOf(refused.name()), confirmationNumber);
        }

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
