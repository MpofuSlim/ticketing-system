package com.innbucks.bookingservice.controller;

import com.innbucks.bookingservice.config.BookingMetrics;
import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.TicketCheckRequestDTO;
import com.innbucks.bookingservice.dto.TicketCheckResponseDTO;
import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.exception.BadRequestException;
import com.innbucks.bookingservice.exception.NotFoundException;
import com.innbucks.bookingservice.repository.BookingRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code POST /bookings/ticket-check}: the key is checked before anything in the
 * body, the answer is a yes or no, and the phone is matched in its stored E.164
 * form. The query itself is pinned against Postgres by
 * {@code TicketCheckAndMyTicketsPostgresIT}.
 */
class TicketCheckControllerTest {

    private static final String KEY = "partner-key-0123456789";
    private static final UUID EVENT = UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6");

    private BookingRepository repository;
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        repository = mock(BookingRepository.class);
        registry = new SimpleMeterRegistry();
    }

    private TicketCheckController controller(String key) {
        return new TicketCheckController(repository, new BookingMetrics(registry), key, "ZW");
    }

    private double count(String outcome) {
        return registry.get(BookingMetrics.TICKET_CHECK).tag("outcome", outcome).counter().count();
    }

    @Test
    void withNoKeyConfigured_theEndpointIsOff() {
        assertThatThrownBy(() -> controller("").check(KEY,
                new TicketCheckRequestDTO("+263771234567", EVENT.toString())))
                .isInstanceOf(NotFoundException.class);
        verify(repository, never()).countHeldBy(any(), any(), any());
    }

    @Test
    void aMissingOrWrongKey_is401_whateverTheBodyHolds() {
        ResponseEntity<ApiResult<TicketCheckResponseDTO>> missing =
                controller(KEY).check(null, new TicketCheckRequestDTO("not a phone", "nope"));
        ResponseEntity<ApiResult<TicketCheckResponseDTO>> wrong =
                controller(KEY).check("partner-key-0123456788", null);

        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(wrong.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(missing.getBody().getMessage()).isEqualTo("Missing or invalid X-Api-Key");
        assertThat(count("rejected")).isEqualTo(2);
        verify(repository, never()).countHeldBy(any(), any(), any());
    }

    @Test
    void withTheKey_aBadPhoneOrEventId_is400_andNothingIsQueried() {
        TicketCheckController controller = controller(KEY);

        assertThatThrownBy(() -> controller.check(KEY, new TicketCheckRequestDTO("12", EVENT.toString())))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("phoneNumber is not a valid phone number.");
        assertThatThrownBy(() -> controller.check(KEY, new TicketCheckRequestDTO(" ", EVENT.toString())))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("phoneNumber is required.");
        assertThatThrownBy(() -> controller.check(KEY, new TicketCheckRequestDTO("+263771234567", null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("eventId is required.");
        assertThatThrownBy(() -> controller.check(KEY, new TicketCheckRequestDTO("+263771234567", "nope")))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("eventId must be a UUID.");
        assertThatThrownBy(() -> controller.check(KEY, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("phoneNumber is required.");

        assertThat(count("invalid")).isEqualTo(5);
        verify(repository, never()).countHeldBy(any(), any(), any());
    }

    @Test
    void aLocalSpelling_isMatchedInItsStoredForm_confirmedOnly() {
        when(repository.countHeldBy(EVENT, "+263771234567", Booking.BookingStatus.CONFIRMED)).thenReturn(2L);

        ResponseEntity<ApiResult<TicketCheckResponseDTO>> response =
                controller(KEY).check(KEY, new TicketCheckRequestDTO("0771234567", EVENT.toString()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getBody().getData()).isEqualTo(new TicketCheckResponseDTO(EVENT, true));
        assertThat(count("held")).isEqualTo(1);
    }

    @Test
    void noTicket_isAPlainNo() {
        when(repository.countHeldBy(any(), any(), any())).thenReturn(0L);

        ResponseEntity<ApiResult<TicketCheckResponseDTO>> response =
                controller(KEY).check(KEY, new TicketCheckRequestDTO("+263771234567", EVENT.toString()));

        assertThat(response.getBody().getData().hasTicket()).isFalse();
        assertThat(count("not_held")).isEqualTo(1);
    }
}
