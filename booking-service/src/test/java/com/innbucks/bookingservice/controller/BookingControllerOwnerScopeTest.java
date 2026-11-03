package com.innbucks.bookingservice.controller;

import com.innbucks.bookingservice.client.UserServiceClient;
import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.BookingItemDTO;
import com.innbucks.bookingservice.dto.BookingResponseDTO;
import com.innbucks.bookingservice.dto.CustomerTicketDTO;
import com.innbucks.bookingservice.dto.TicketWindow;
import com.innbucks.bookingservice.exception.BadRequestException;
import com.innbucks.bookingservice.exception.NotFoundException;
import com.innbucks.bookingservice.security.JwtAuthDetails;
import com.innbucks.bookingservice.service.BookingService;
import com.innbucks.bookingservice.service.EventChangeNotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Owner-scoping guard for the two lookups that used to be public and leaked
 * another customer's full PII + the scannable ticket QR keyed on a low-entropy
 * identifier (OWASP A01 / BOLA):
 *
 * <ul>
 *   <li>{@code GET /bookings/phone/{phoneNumber}} — a CUSTOMER may only query
 *       the phone number on their own JWT (403 otherwise); platform staff query
 *       any; an EVENT_ORGANIZER queries any but gets only their own events'
 *       bookings.</li>
 *   <li>{@code GET /bookings/confirmation/{number}} — the controller delegates
 *       with the caller's identity, an isAdmin flag (platform staff only) and
 *       the organizer's id, so the service can scope (404 for anyone else).</li>
 * </ul>
 *
 * <p>Both endpoints now require authentication (SecurityConfig no longer
 * permitAll's the paths), so an unauthenticated call is a Spring-Security 401
 * before the controller runs — these unit tests exercise the authenticated
 * ownership branches directly.
 */
class BookingControllerOwnerScopeTest {

    private static final String OWNER_EMAIL = "alice@example.com";
    private static final String OWNER_PHONE_E164 = "+263782606983";
    private static final String OWNER_PHONE_LOCAL = "0782606983"; // same number, ZW local form
    private static final String OTHER_PHONE_E164 = "+263770000000";
    private static final UUID ORGANIZER_UUID = UUID.fromString("0b9e6d3c-5a4f-4e21-9c8b-7a6f5e4d3c2b");

    private static BookingController controller(BookingService bookingService) {
        BookingController c = new BookingController(
                bookingService,
                mock(UserServiceClient.class),
                mock(EventChangeNotificationService.class));
        // No Spring context: set the cell country so E.164 normalisation of
        // local-form numbers resolves against ZW (matches the ZW cell).
        ReflectionTestUtils.setField(c, "deploymentCountry", "ZW");
        return c;
    }

    /** JWT-authenticated caller with the given email, phone claim and roles. */
    private static Authentication auth(String email, String phone, String... roles) {
        return auth(email, phone, null, roles);
    }

    /** As above, with the token's organizerUuid claim. */
    private static Authentication auth(String email, String phone, UUID organizerUuid, String... roles) {
        List<SimpleGrantedAuthority> authorities = Arrays.stream(roles)
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toList());
        var token = new UsernamePasswordAuthenticationToken(email, null, authorities);
        token.setDetails(new JwtAuthDetails(email, phone, null, organizerUuid, null, null));
        return token;
    }

    private static BookingResponseDTO bookingWithQr() {
        return BookingResponseDTO.builder()
                .id(UUID.randomUUID())
                .userEmail(OWNER_EMAIL)
                .phoneNumber(OWNER_PHONE_E164)
                .confirmationNumber("INN-20260502-AB12CD")
                .status(com.innbucks.bookingservice.entity.Booking.BookingStatus.CONFIRMED)
                .totalAmount(new BigDecimal("100.00"))
                .items(List.of(BookingItemDTO.builder()
                        .ticketNumber("20260502-12345A")
                        .qrCode("data:image/png;base64,QRQRQR")
                        .build()))
                .build();
    }

    // ---------------- GET /bookings/phone/{phoneNumber} ----------------

    @Test
    void phone_owner_getsOwnBookingsWithQr_andLookupUsesNormalisedNumber() {
        BookingService bookingService = mock(BookingService.class);
        when(bookingService.getActiveByPhoneNumber(OWNER_PHONE_E164, true))
                .thenReturn(List.of(bookingWithQr()));

        // Owner queries in LOCAL form; JWT carries the E.164 form. They match
        // after normalisation, and the service is queried with the E.164 form.
        ResponseEntity<ApiResult<List<BookingResponseDTO>>> resp =
                controller(bookingService).getBookingsByPhoneNumber(
                        OWNER_PHONE_LOCAL, auth(OWNER_EMAIL, OWNER_PHONE_E164, "ROLE_CUSTOMER"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().getData()).hasSize(1);
        assertThat(resp.getBody().getData().get(0).getItems().get(0).getQrCode())
                .startsWith("data:image/png;base64,");
        verify(bookingService).getActiveByPhoneNumber(OWNER_PHONE_E164, true);
    }

    @Test
    void phone_customerQueryingAnotherPhone_is403_andNeverHitsTheService() {
        BookingService bookingService = mock(BookingService.class);

        // Caller's JWT phone is OWNER_PHONE; they ask for someone else's number.
        assertThatThrownBy(() -> controller(bookingService).getBookingsByPhoneNumber(
                OTHER_PHONE_E164, auth(OWNER_EMAIL, OWNER_PHONE_E164, "ROLE_CUSTOMER")))
                .isInstanceOf(AccessDeniedException.class);

        verify(bookingService, never()).getActiveByPhoneNumber(any(), anyBoolean());
    }

    @Test
    void phone_customerWithNoPhoneClaim_is403() {
        BookingService bookingService = mock(BookingService.class);

        // Token carries no phone claim -> can't prove ownership -> fail closed.
        assertThatThrownBy(() -> controller(bookingService).getBookingsByPhoneNumber(
                OWNER_PHONE_E164, auth(OWNER_EMAIL, null, "ROLE_CUSTOMER")))
                .isInstanceOf(AccessDeniedException.class);

        verify(bookingService, never()).getActiveByPhoneNumber(any(), anyBoolean());
    }

    @Test
    void phone_eventOrganizer_getsOnlyTheirOwnEventsBookings_forAnotherNumber() {
        BookingService bookingService = mock(BookingService.class);
        when(bookingService.getActiveByPhoneNumberForOrganizer(OTHER_PHONE_E164, ORGANIZER_UUID))
                .thenReturn(List.of(bookingWithQr()));

        // Organizer (support) queries a customer's number that is NOT their own.
        ResponseEntity<ApiResult<List<BookingResponseDTO>>> resp =
                controller(bookingService).getBookingsByPhoneNumber(OTHER_PHONE_E164,
                        auth("org@example.com", OWNER_PHONE_E164, ORGANIZER_UUID, "ROLE_EVENT_ORGANIZER"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(bookingService).getActiveByPhoneNumberForOrganizer(OTHER_PHONE_E164, ORGANIZER_UUID);
        // The unscoped lookup returned every organizer's bookings for the number.
        verify(bookingService, never()).getActiveByPhoneNumber(any(), anyBoolean());
    }

    @Test
    void phone_eventOrganizer_readingTheirOwnNumber_getsAllTheirOwnBookings() {
        BookingService bookingService = mock(BookingService.class);
        when(bookingService.getActiveByPhoneNumber(OWNER_PHONE_E164, true)).thenReturn(List.of(bookingWithQr()));

        // Their own tickets, bought as a customer, whoever organizes those events.
        controller(bookingService).getBookingsByPhoneNumber(OWNER_PHONE_LOCAL,
                auth("org@example.com", OWNER_PHONE_E164, ORGANIZER_UUID, "ROLE_EVENT_ORGANIZER"));

        verify(bookingService).getActiveByPhoneNumber(OWNER_PHONE_E164, true);
        verify(bookingService, never()).getActiveByPhoneNumberForOrganizer(any(), any());
    }

    @Test
    void phone_eventOrganizerWithNoOrganizerUuid_is403() {
        BookingService bookingService = mock(BookingService.class);

        assertThatThrownBy(() -> controller(bookingService).getBookingsByPhoneNumber(
                OTHER_PHONE_E164, auth("org@example.com", null, "ROLE_EVENT_ORGANIZER")))
                .isInstanceOf(AccessDeniedException.class);

        verify(bookingService, never()).getActiveByPhoneNumber(any(), anyBoolean());
        verify(bookingService, never()).getActiveByPhoneNumberForOrganizer(any(), any());
    }

    @Test
    void phone_productOfficer_mayQueryAnyPhone_unscoped() {
        BookingService bookingService = mock(BookingService.class);
        when(bookingService.getActiveByPhoneNumber(OTHER_PHONE_E164, false)).thenReturn(List.of());

        controller(bookingService).getBookingsByPhoneNumber(
                OTHER_PHONE_E164, auth("officer@innbucks.co.zw", null, "ROLE_PRODUCT_OFFICER"));

        verify(bookingService).getActiveByPhoneNumber(OTHER_PHONE_E164, false);
    }

    @Test
    void phone_superAdmin_mayQueryAnyPhone() {
        BookingService bookingService = mock(BookingService.class);
        when(bookingService.getActiveByPhoneNumber(OTHER_PHONE_E164, false))
                .thenReturn(List.of());

        ResponseEntity<ApiResult<List<BookingResponseDTO>>> resp =
                controller(bookingService).getBookingsByPhoneNumber(
                        OTHER_PHONE_E164, auth("admin@example.com", null, "ROLE_SUPER_ADMIN"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(bookingService).getActiveByPhoneNumber(OTHER_PHONE_E164, false);
    }

    // ---------------- GET /bookings/me/tickets ----------------

    @Test
    void myTickets_readsTheTokensPhone_inStoredForm_withTheFilter() {
        BookingService bookingService = mock(BookingService.class);
        when(bookingService.getPublicTicketsByPhoneNumber(OWNER_PHONE_E164, TicketWindow.UPCOMING))
                .thenReturn(List.of());

        // The claim in local form still reaches the service as E.164.
        ResponseEntity<ApiResult<List<CustomerTicketDTO>>> resp = controller(bookingService)
                .getMyTickets("upcoming", auth(OWNER_EMAIL, OWNER_PHONE_LOCAL, "ROLE_CUSTOMER"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getHeaders().getCacheControl()).isEqualTo("no-store");
        verify(bookingService).getPublicTicketsByPhoneNumber(OWNER_PHONE_E164, TicketWindow.UPCOMING);
    }

    @Test
    void myTickets_aSessionWithNoPhone_is400_andReadsNothing() {
        BookingService bookingService = mock(BookingService.class);

        assertThatThrownBy(() -> controller(bookingService)
                .getMyTickets(null, auth("admin@example.com", null, "ROLE_SUPER_ADMIN")))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Your session has no phone number. Sign in with your phone to see your tickets.");
        verify(bookingService, never()).getPublicTicketsByPhoneNumber(any(), any());
    }

    // ---------------- GET /bookings/confirmation/{number} ----------------

    @Test
    void confirmation_customer_delegatesWithCallerIdentity_andIsAdminFalse() {
        BookingService bookingService = mock(BookingService.class);
        when(bookingService.getByConfirmationNumber(
                eq("INN-20260502-AB12CD"), eq(OWNER_EMAIL), eq(OWNER_PHONE_E164), eq(false), isNull()))
                .thenReturn(bookingWithQr());

        ResponseEntity<ApiResult<BookingResponseDTO>> resp =
                controller(bookingService).getByConfirmationNumber(
                        "INN-20260502-AB12CD", auth(OWNER_EMAIL, OWNER_PHONE_E164, "ROLE_CUSTOMER"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().getData().getItems().get(0).getQrCode())
                .startsWith("data:image/png;base64,");
        // Owner-scoped: the caller's identity + isAdmin=false reach the service,
        // which is where the 404-for-non-owner decision is made.
        verify(bookingService).getByConfirmationNumber(
                "INN-20260502-AB12CD", OWNER_EMAIL, OWNER_PHONE_E164, false, null);
    }

    @Test
    void confirmation_nonOwnerCustomer_propagatesNotFound() {
        BookingService bookingService = mock(BookingService.class);
        // Service fail-quiets a non-owner with 404 (NotFoundException); the
        // controller must let it propagate to the 404 handler.
        when(bookingService.getByConfirmationNumber(any(), any(), any(), eq(false), any()))
                .thenThrow(new NotFoundException("Booking not found"));

        assertThatThrownBy(() -> controller(bookingService).getByConfirmationNumber(
                "INN-20260502-AB12CD", auth("mallory@example.com", OTHER_PHONE_E164, "ROLE_CUSTOMER")))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Booking not found");
    }

    @Test
    void confirmation_superAdmin_delegatesWithIsAdminTrue() {
        BookingService bookingService = mock(BookingService.class);
        when(bookingService.getByConfirmationNumber(any(), any(), any(), eq(true), any()))
                .thenReturn(bookingWithQr());

        ResponseEntity<ApiResult<BookingResponseDTO>> resp =
                controller(bookingService).getByConfirmationNumber(
                        "INN-20260502-AB12CD", auth("admin@example.com", null, "ROLE_SUPER_ADMIN"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(bookingService).getByConfirmationNumber(
                eq("INN-20260502-AB12CD"), eq("admin@example.com"), any(), eq(true), isNull());
    }

    @Test
    void confirmation_eventOrganizer_isNotAdmin_andPassesTheirOrganizerId() {
        BookingService bookingService = mock(BookingService.class);
        when(bookingService.getByConfirmationNumber(any(), any(), any(), eq(false), eq(ORGANIZER_UUID)))
                .thenReturn(bookingWithQr());

        ResponseEntity<ApiResult<BookingResponseDTO>> resp =
                controller(bookingService).getByConfirmationNumber("INN-20260502-AB12CD",
                        auth("org@example.com", null, ORGANIZER_UUID, "ROLE_EVENT_ORGANIZER"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        // isAdmin=false: the service scopes the organizer to their own events.
        verify(bookingService).getByConfirmationNumber(any(), any(), any(), eq(false), eq(ORGANIZER_UUID));
    }

    @Test
    void confirmation_aCustomerTokenCarryingAnOrganizerUuid_passesNoOrganizerScope() {
        BookingService bookingService = mock(BookingService.class);
        when(bookingService.getByConfirmationNumber(any(), any(), any(), eq(false), isNull()))
                .thenReturn(bookingWithQr());

        // The organizer scope comes with the role, never with the claim alone.
        controller(bookingService).getByConfirmationNumber("INN-20260502-AB12CD",
                auth(OWNER_EMAIL, OWNER_PHONE_E164, ORGANIZER_UUID, "ROLE_CUSTOMER"));

        verify(bookingService).getByConfirmationNumber(any(), any(), any(), eq(false), isNull());
    }
}
