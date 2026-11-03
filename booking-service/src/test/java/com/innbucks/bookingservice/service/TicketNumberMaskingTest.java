package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.client.SeatServiceClient;
import com.innbucks.bookingservice.config.MarketTimeZone;
import com.innbucks.bookingservice.dto.AttendeeUpdateRequestDTO;
import com.innbucks.bookingservice.dto.AttendeeUpdateResponseDTO;
import com.innbucks.bookingservice.dto.BookingItemDTO;
import com.innbucks.bookingservice.dto.BookingResponseDTO;
import com.innbucks.bookingservice.dto.CategoryBookingDTO;
import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.entity.BookingItem;
import com.innbucks.bookingservice.entity.ScanAttempt;
import com.innbucks.bookingservice.repository.BookingItemRepository;
import com.innbucks.bookingservice.repository.BookingRepository;
import com.innbucks.bookingservice.repository.CategoryInventoryRepository;
import com.innbucks.bookingservice.repository.ScanAttemptRepository;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A ticket number is the gate credential. With the cell's masking switch on,
 * every surface an organizer or staff member reads (the guest list, a booking
 * read by someone other than its buyer, the scan reports, the attendee edit's
 * answer) shows it masked and carries no QR; the buyer, the holder and sibling
 * services still get it whole. With the switch off nothing changes, which is
 * what keeps production as it is until the console has moved.
 */
class TicketNumberMaskingTest {

    private static final String TICKET = "20261030-CH2F0-5V7CB";
    private static final String MASKED = "20261030-*****-**7CB";

    private final BookingRepository bookingRepo = mock(BookingRepository.class);
    private final BookingItemRepository itemRepo = mock(BookingItemRepository.class);
    private final UUID eventId = UUID.randomUUID();

    private BookingService service(boolean mask) {
        BookingService service = new BookingService(bookingRepo, itemRepo, mock(CategoryInventoryRepository.class),
                mock(SeatServiceClient.class), mock(ApplicationEventPublisher.class), new QrCodeGenerator(), null,
                mock(PlatformTransactionManager.class));
        service.setTicketNumberDisplay(new TicketNumberDisplay(mask));
        return service;
    }

    private Booking booking() {
        Booking booking = Booking.builder()
                .id(UUID.randomUUID())
                .eventId(eventId)
                .userEmail("alice@example.com")
                .phoneNumber("+263772000111")
                .confirmationNumber("INN-20261030-3C8849")
                .status(Booking.BookingStatus.CONFIRMED)
                .totalAmount(new BigDecimal("100.00"))
                .build();
        BookingItem item = BookingItem.builder()
                .id(UUID.randomUUID())
                .booking(booking)
                .categoryName("VIP")
                .priceAtBooking(new BigDecimal("100.00"))
                .ticketNumber(TICKET)
                .build();
        booking.setItems(List.of(item));
        return booking;
    }

    // ------------------------------------------------------------------ guest list

    @Test
    void guestList_masked_carriesTheItemId_andNoFullNumber() {
        Booking booking = booking();
        when(itemRepo.findByEventIdWithBooking(eventId)).thenReturn(booking.getItems());

        CategoryBookingDTO row = service(true).getBookingsByEvent(eventId, null, true).get(0);

        assertThat(row.getTicketNumber()).isEqualTo(MASKED);
        assertThat(row.getBookingItemId()).isEqualTo(booking.getItems().get(0).getId());
    }

    @Test
    void guestList_switchOff_isAsBefore() {
        Booking booking = booking();
        when(itemRepo.findByEventIdWithBooking(eventId)).thenReturn(booking.getItems());

        assertThat(service(false).getBookingsByEvent(eventId, null, true).get(0).getTicketNumber())
                .isEqualTo(TICKET);
    }

    // ------------------------------------------------------------------ search

    @Test
    void search_aTypedNumber_findsThatTicketsRowOnly_masked() {
        Booking booking = booking();
        when(itemRepo.findByTicketNumberWithBooking(TICKET)).thenReturn(Optional.of(booking.getItems().get(0)));

        List<CategoryBookingDTO> rows = service(true).findInEventByTicketNumber(eventId, "20261030 ch2f0 5v7cb", null, true);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getTicketNumber()).isEqualTo(MASKED);
    }

    @Test
    void search_anotherEventsTicket_isEmpty() {
        Booking booking = booking();
        when(itemRepo.findByTicketNumberWithBooking(TICKET)).thenReturn(Optional.of(booking.getItems().get(0)));

        assertThat(service(true).findInEventByTicketNumber(UUID.randomUUID(), TICKET, null, true)).isEmpty();
    }

    @Test
    void search_somethingThatIsNotATicket_isEmpty_withoutALookup() {
        assertThat(service(true).findInEventByTicketNumber(eventId, "alice", null, true)).isEmpty();
        verify(itemRepo, never()).findByTicketNumberWithBooking(anyString());
    }

    @Test
    void search_byANonOwner_isRefusedBeforeAnyLookup() {
        // No event-service client: ownership cannot be confirmed, so it is refused.
        assertThatThrownBy(() -> service(true).findInEventByTicketNumber(eventId, TICKET, UUID.randomUUID(), false))
                .isInstanceOf(AccessDeniedException.class);
        verify(itemRepo, never()).findByTicketNumberWithBooking(anyString());
    }

    // ------------------------------------------------------------------ booking reads

    @Test
    void confirmationLookup_byStaff_isMasked_withNoQr_butTheItemId() {
        Booking booking = booking();
        when(bookingRepo.findByConfirmationNumberWithItems("INN-20261030-3C8849")).thenReturn(Optional.of(booking));

        BookingItemDTO item = service(true)
                .getByConfirmationNumber("INN-20261030-3C8849", "support@innbucks.co.zw", null, true)
                .getItems().get(0);

        assertThat(item.getTicketNumber()).isEqualTo(MASKED);
        assertThat(item.getQrCode()).isNull();
        assertThat(item.getBookingItemId()).isEqualTo(booking.getItems().get(0).getId());
    }

    @Test
    void confirmationLookup_byTheBuyer_isWhole_withTheQr() {
        when(bookingRepo.findByConfirmationNumberWithItems("INN-20261030-3C8849")).thenReturn(Optional.of(booking()));

        BookingItemDTO item = service(true)
                .getByConfirmationNumber("INN-20261030-3C8849", null, "+263772000111", false)
                .getItems().get(0);

        assertThat(item.getTicketNumber()).isEqualTo(TICKET);
        assertThat(item.getQrCode()).startsWith("data:image/png;base64,");
    }

    @Test
    void bookingById_byStaff_isMasked_byItsBuyer_isWhole() {
        Booking booking = booking();
        when(bookingRepo.findByIdWithItems(booking.getId())).thenReturn(Optional.of(booking));

        assertThat(service(true).getBookingById(booking.getId(), "support@innbucks.co.zw", true)
                .getItems().get(0).getQrCode()).isNull();
        assertThat(service(true).getBookingById(booking.getId(), "alice@example.com", true)
                .getItems().get(0).getTicketNumber()).isEqualTo(TICKET);
    }

    @Test
    void theInternalServiceRead_isNeverMasked() {
        Booking booking = booking();
        when(bookingRepo.findByIdWithItems(booking.getId())).thenReturn(Optional.of(booking));

        BookingResponseDTO dto = service(true).getBookingForService(booking.getId());

        assertThat(dto.getItems().get(0).getTicketNumber()).isEqualTo(TICKET);
        assertThat(dto.getItems().get(0).getQrCode()).isNotNull();
    }

    @Test
    void phoneLookup_staffReadingSomeoneElsesNumber_isMasked_theirOwnIsWhole() {
        when(bookingRepo.findByPhoneNumberOrderByCreatedAtDesc("+263772000111")).thenReturn(List.of(booking()));

        assertThat(service(true).getActiveByPhoneNumber("+263772000111", false).get(0).getItems().get(0)
                .getTicketNumber()).isEqualTo(MASKED);
        assertThat(service(true).getActiveByPhoneNumber("+263772000111", true).get(0).getItems().get(0)
                .getTicketNumber()).isEqualTo(TICKET);
    }

    @Test
    void phoneLookup_byAnOrganizer_isMasked() {
        UUID organizer = UUID.randomUUID();
        when(bookingRepo.findByPhoneNumberAndTenantUserUuidOrderByCreatedAtDesc("+263772000111", organizer))
                .thenReturn(List.of(booking()));

        assertThat(service(true).getActiveByPhoneNumberForOrganizer("+263772000111", organizer).get(0)
                .getItems().get(0).getQrCode()).isNull();
    }

    @Test
    void theBuyersPublicViews_carryNoItemId() {
        Booking booking = booking();
        when(bookingRepo.findById(booking.getId())).thenReturn(Optional.of(booking));
        when(bookingRepo.findByIdWithItems(booking.getId())).thenReturn(Optional.of(booking));

        assertThat(service(true).getBookingByIdPublic(booking.getId()).getItems().get(0).getTicketNumber())
                .isEqualTo(TICKET);
    }

    // ------------------------------------------------------------------ scan reports

    @Test
    void scanReports_areMasked() {
        ScanAttemptRepository repo = mock(ScanAttemptRepository.class);
        ScanReportService reports = new ScanReportService(repo, new MarketTimeZone("ZW"));
        reports.setTicketNumberDisplay(new TicketNumberDisplay(true));
        ScanAttempt attempt = ScanAttempt.builder().id(UUID.randomUUID()).attemptedAt(Instant.now())
                .outcome(ScanAttempt.Outcome.WRONG_EVENT_DAY).ticketNumber(TICKET).build();
        when(repo.findByEventIdAndAttemptedAtBetween(any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(attempt)));

        assertThat(reports.listEventScans(eventId, Instant.EPOCH, Instant.now(), 0, 20).content().get(0).ticketNumber())
                .isEqualTo(MASKED);
    }

    // ------------------------------------------------------------------ attendee edit

    @Test
    void attendeeEdit_byItemId_editsThatTicket_andAnswersMasked() {
        Booking booking = booking();
        BookingItem item = booking.getItems().get(0);
        when(bookingRepo.lockById(booking.getId())).thenReturn(Optional.of(booking));
        AttendeeEditService edits = new AttendeeEditService(bookingRepo, "ZW");
        edits.setTicketNumberDisplay(new TicketNumberDisplay(true));
        var staff = new UsernamePasswordAuthenticationToken("admin@innbucks.co.zw", null,
                List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN")));

        AttendeeUpdateResponseDTO answer = edits.updateByItem(staff, booking.getId(), item.getId(),
                new AttendeeUpdateRequestDTO("Rudo Sibanda", null, "0772000222"));

        assertThat(item.getAttendeeName()).isEqualTo("Rudo Sibanda");
        assertThat(item.getAttendeePhone()).isEqualTo("+263772000222");
        assertThat(answer.ticketNumber()).isEqualTo(MASKED);
    }

    @Test
    void attendeeEdit_byAnItemOfAnotherBooking_isNotFound() {
        Booking booking = booking();
        when(bookingRepo.lockById(booking.getId())).thenReturn(Optional.of(booking));
        AttendeeEditService edits = new AttendeeEditService(bookingRepo, "ZW");
        var staff = new UsernamePasswordAuthenticationToken("admin@innbucks.co.zw", null,
                List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN")));

        assertThatThrownBy(() -> edits.updateByItem(staff, booking.getId(), UUID.randomUUID(),
                new AttendeeUpdateRequestDTO("Rudo Sibanda", null, null)))
                .hasMessage("Ticket not found on this booking");
    }
}
