package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.dto.AttendeeUpdateRequestDTO;
import com.innbucks.bookingservice.dto.AttendeeUpdateResponseDTO;
import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.entity.BookingItem;
import com.innbucks.bookingservice.exception.BadRequestException;
import com.innbucks.bookingservice.exception.NotFoundException;
import com.innbucks.bookingservice.repository.BookingRepository;
import com.innbucks.bookingservice.security.JwtAuthDetails;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AttendeeEditServiceTest {

    private static final String TICKET = "20261020-97554W";

    private final BookingRepository repo = mock(BookingRepository.class);
    private final AttendeeEditService service = new AttendeeEditService(repo, "ZW");

    private static UsernamePasswordAuthenticationToken auth(String role, UUID organizerUuid) {
        var a = new UsernamePasswordAuthenticationToken("caller@example.com", null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        a.setDetails(new JwtAuthDetails("caller@example.com", null, UUID.randomUUID(), organizerUuid, "A", "B"));
        return a;
    }

    private Booking booking(UUID tenant, Booking.BookingStatus status) {
        Booking b = new Booking();
        b.setId(UUID.randomUUID());
        b.setStatus(status);
        b.setTenantUserUuid(tenant);
        b.setConfirmationNumber("INN-20261020-3BD788");
        BookingItem i = new BookingItem();
        i.setTicketNumber(TICKET);
        i.setAttendeeName("Joel Mandingaisa");
        i.setAttendeePhone("+263773863024");
        i.setBooking(b);
        b.setItems(new ArrayList<>(List.of(i)));
        when(repo.lockById(b.getId())).thenReturn(Optional.of(b));
        return b;
    }

    @Test
    void superAdmin_correctsThePhone_storedE164_andReportsWhatChanged() {
        Booking b = booking(UUID.randomUUID(), Booking.BookingStatus.CONFIRMED);

        AttendeeUpdateResponseDTO r = service.update(auth("SUPER_ADMIN", null), b.getId(), TICKET,
                new AttendeeUpdateRequestDTO("Joel Mandingaisa", null, "0713664366"));

        assertThat(r.attendeePhone()).isEqualTo("+263713664366");
        assertThat(b.getItems().get(0).getAttendeePhone()).isEqualTo("+263713664366");
        assertThat(r.changed()).containsExactly("phoneNumber");
    }

    @Test
    void blankFields_clearTheContact() {
        Booking b = booking(null, Booking.BookingStatus.CONFIRMED);

        AttendeeUpdateResponseDTO r = service.update(auth("SUPER_ADMIN", null), b.getId(), TICKET,
                new AttendeeUpdateRequestDTO(" Joel M ", " ", ""));

        assertThat(r.attendeeName()).isEqualTo("Joel M");
        assertThat(r.attendeePhone()).isNull();
        assertThat(r.changed()).containsExactly("fullName", "phoneNumber");
    }

    @Test
    void owningOrganizer_mayEdit_otherOrganizer_andProductOfficer_mayNot() {
        UUID owner = UUID.randomUUID();
        Booking b = booking(owner, Booking.BookingStatus.CONFIRMED);
        var req = new AttendeeUpdateRequestDTO("Joel Mandingaisa", null, "+263713664366");

        assertThat(service.update(auth("EVENT_ORGANIZER", owner), b.getId(), TICKET, req).changed())
                .containsExactly("phoneNumber");
        assertThatThrownBy(() -> service.update(auth("EVENT_ORGANIZER", UUID.randomUUID()), b.getId(), TICKET, req))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.update(auth("PRODUCT_OFFICER", null), b.getId(), TICKET, req))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void refusesBadPhone_cancelledBooking_usedTicket_unknownTicket() {
        Booking b = booking(null, Booking.BookingStatus.CONFIRMED);
        var admin = auth("SUPER_ADMIN", null);

        assertThatThrownBy(() -> service.update(admin, b.getId(), TICKET,
                new AttendeeUpdateRequestDTO("Joel", null, "12345")))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("doesn't look valid");
        assertThatThrownBy(() -> service.update(admin, b.getId(), "20261020-XXXXXX",
                new AttendeeUpdateRequestDTO("Joel", null, null)))
                .isInstanceOf(NotFoundException.class);

        b.getItems().get(0).setRedeemedAt(LocalDateTime.now(ZoneOffset.UTC));
        assertThatThrownBy(() -> service.update(admin, b.getId(), TICKET,
                new AttendeeUpdateRequestDTO("Joel", null, null)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("used at the gate");

        Booking c = booking(null, Booking.BookingStatus.CANCELLED);
        assertThatThrownBy(() -> service.update(admin, c.getId(), TICKET,
                new AttendeeUpdateRequestDTO("Joel", null, null)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("cancelled");
    }
}
