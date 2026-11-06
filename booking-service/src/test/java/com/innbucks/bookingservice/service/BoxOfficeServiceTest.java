package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.client.EventServiceClient;
import com.innbucks.bookingservice.config.BookingMetrics;
import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.BookingResponseDTO;
import com.innbucks.bookingservice.dto.BoxOfficeSaleRequestDTO;
import com.innbucks.bookingservice.dto.EventLookupDTO;
import com.innbucks.bookingservice.dto.EventSalesWindowDTO;
import com.innbucks.bookingservice.dto.SellerAccessDTO;
import com.innbucks.bookingservice.exception.ApiProblemException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * The box office (V35, V36): who sells is decided per sale. The event's own
 * organizer keeps the V35 path; anyone else must be a sales agent for the
 * event, asked live of event-service, and every check fails closed.
 */
class BoxOfficeServiceTest {

    private static final UUID EVENT = UUID.randomUUID();
    private static final UUID ORGANIZER = UUID.randomUUID();
    private static final UUID AGENT_ACCOUNT = UUID.randomUUID();
    private static final UUID AGENT_ROW = UUID.randomUUID();

    private final BookingService bookings = mock(BookingService.class);
    private final EventServiceClient events = mock(EventServiceClient.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @SuppressWarnings("unchecked")
    private BoxOfficeService service() {
        ObjectProvider<EventServiceClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(events);
        BoxOfficeService service = new BoxOfficeService(bookings, provider, "token");
        service.setMetrics(new BookingMetrics(registry));
        return service;
    }

    private static BoxOfficeSaleRequestDTO request() {
        BoxOfficeSaleRequestDTO r = new BoxOfficeSaleRequestDTO();
        r.setEventId(EVENT);
        return r;
    }

    private static BoxOfficeSaleRequestDTO online() {
        BoxOfficeSaleRequestDTO r = request();
        r.setPaymentMethod(BoxOfficeSaleRequestDTO.PaymentMethod.ONLINE);
        return r;
    }

    private static BoxOfficeService.Caller organizer() {
        return new BoxOfficeService.Caller(ORGANIZER, ORGANIZER, true, "organizer@example.com");
    }

    /** A super-app session: CUSTOMER only, no organizer claim, no email. */
    private static BoxOfficeService.Caller appAgent() {
        return new BoxOfficeService.Caller(AGENT_ACCOUNT, null, false, null);
    }

    private void window(String status, UUID tenant) {
        when(events.salesWindow(eq(EVENT), anyString())).thenReturn(ApiResult.ok("ok",
                EventSalesWindowDTO.builder().eventId(EVENT).status(status).tenantUserUuid(tenant).build()));
    }

    private void sellerAccess(SellerAccessDTO answer) {
        when(events.sellerAccess(eq(AGENT_ACCOUNT), eq(EVENT), anyString()))
                .thenReturn(ApiResult.ok("Seller access", answer));
    }

    private static SellerAccessDTO allowed(Integer cap, String percent) {
        return SellerAccessDTO.builder().allowed(true).agentId(AGENT_ROW).organizerUuid(ORGANIZER)
                .displayName("Tendai Moyo").ticketCap(cap)
                .commissionPercent(percent == null ? null : new BigDecimal(percent)).build();
    }

    private BookingResponseDTO held() {
        UUID id = UUID.randomUUID();
        BookingResponseDTO dto = BookingResponseDTO.builder().id(id).viewToken("3f9c2a7e1b4d4e8fa0c6d2b9e5f71a34")
                .build();
        when(bookings.createBoxOfficeBooking(any(), any(), any(), any(), anyBoolean())).thenReturn(dto);
        return dto;
    }

    private double agentSales(String outcome) {
        return registry.get(BookingMetrics.AGENT_SALE).tag("outcome", outcome).counter().count();
    }

    // ---- the organizer ---------------------------------------------------------

    @Test
    void theOrganizer_sellsForCash_andTheSaleIsConfirmedAsTheirs() {
        window(EventSalesWindowDTO.ON_SALE, ORGANIZER);
        BookingResponseDTO held = held();
        BookingResponseDTO sold = BookingResponseDTO.builder().id(held.getId()).build();
        when(bookings.confirmBoxOfficeSale(held.getId(), ORGANIZER, "organizer@example.com")).thenReturn(sold);

        assertSame(sold, service().sell(request(), "buyer@example.com", "+263771234567", organizer()));

        ArgumentCaptor<BoxOfficeSeller> seller = ArgumentCaptor.forClass(BoxOfficeSeller.class);
        verify(bookings).createBoxOfficeBooking(eq("buyer@example.com"), eq("+263771234567"), any(),
                seller.capture(), eq(false));
        assertFalse(seller.getValue().isAgent());
        assertEquals(ORGANIZER, seller.getValue().userUuid());
        assertEquals("organizer@example.com", seller.getValue().name());
        verify(events, never()).sellerAccess(any(), any(), any());
        assertEquals(0.0, agentSales("sold_cash"), "the organizer's own sale is not an agent's");
    }

    @Test
    void theOrganizer_sellsOnline_theBookingStaysPendingWithItsViewToken_andIsNotConfirmed() {
        window(EventSalesWindowDTO.ON_SALE, ORGANIZER);
        BookingResponseDTO held = held();

        BookingResponseDTO answer = service().sell(online(), null, "+263771234567", organizer());

        assertSame(held, answer);
        verify(bookings).createBoxOfficeBooking(any(), any(), any(), any(), eq(true));
        verify(bookings, never()).confirmBoxOfficeSale(any(), any(), any());
    }

    @Test
    void theOrganizer_mayStillUseAPromoCode() {
        window(EventSalesWindowDTO.ON_SALE, ORGANIZER);
        BookingResponseDTO held = held();
        when(bookings.confirmBoxOfficeSale(any(), any(), any())).thenReturn(held);
        BoxOfficeSaleRequestDTO withCode = request();
        withCode.setPromoCode("EARLY10");

        service().sell(withCode, null, "+263771234567", organizer());

        verify(bookings).createBoxOfficeBooking(any(), any(), eq(withCode), any(), eq(false));
    }

    @Test
    void anUnknownEvent_andAnUnansweredQuestion_sellNothing_forTheOrganizer() {
        window(EventSalesWindowDTO.NOT_FOUND, null);
        ApiProblemException unknown = assertThrows(ApiProblemException.class,
                () -> service().sell(request(), null, "+263771234567", organizer()));
        assertEquals(HttpStatus.NOT_FOUND, unknown.getStatus());

        when(events.salesWindow(eq(EVENT), anyString())).thenReturn(ApiResult.ok("down", null));
        ApiProblemException down = assertThrows(ApiProblemException.class,
                () -> service().sell(request(), null, "+263771234567", organizer()));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, down.getStatus());
        assertEquals("TRY_AGAIN", down.getErrorCode());
        verify(bookings, never()).createBoxOfficeBooking(any(), any(), any(), any(), anyBoolean());
    }

    // ---- sales agents ------------------------------------------------------------

    @Test
    void anAppAgent_isAskedAboutLive_andSellsAsTheAgent_withTheirCapAndCommission() {
        sellerAccess(allowed(200, "5.00"));
        BookingResponseDTO held = held();
        BookingResponseDTO sold = BookingResponseDTO.builder().id(held.getId()).build();
        when(bookings.confirmBoxOfficeSale(held.getId(), AGENT_ACCOUNT, "Tendai Moyo")).thenReturn(sold);

        assertSame(sold, service().sell(request(), null, "+263771234567", appAgent()));

        ArgumentCaptor<BoxOfficeSeller> seller = ArgumentCaptor.forClass(BoxOfficeSeller.class);
        verify(bookings).createBoxOfficeBooking(any(), eq("+263771234567"), any(), seller.capture(), eq(false));
        BoxOfficeSeller s = seller.getValue();
        assertTrue(s.isAgent());
        assertEquals(AGENT_ACCOUNT, s.userUuid());
        assertEquals("Tendai Moyo", s.name());
        assertEquals(AGENT_ROW, s.agentId());
        assertEquals(ORGANIZER, s.organizerUuid());
        assertEquals(200, s.ticketCap());
        assertEquals(new BigDecimal("5.00"), s.commissionPercent());
        // A non-organizer session never asks the sales window here (the booking does).
        verify(events, never()).salesWindow(any(), any());
        assertEquals(1.0, agentSales("sold_cash"));
    }

    @Test
    void anAgentSellingOnline_isCountedOnline_andNotConfirmed() {
        sellerAccess(allowed(null, null));
        held();

        service().sell(online(), null, "+263771234567", appAgent());

        ArgumentCaptor<BoxOfficeSeller> seller = ArgumentCaptor.forClass(BoxOfficeSeller.class);
        verify(bookings).createBoxOfficeBooking(any(), any(), any(), seller.capture(), eq(true));
        assertNull(seller.getValue().ticketCap());
        assertEquals(new BigDecimal("0.00"), seller.getValue().commissionPercent(), "no percentage = 0");
        verify(bookings, never()).confirmBoxOfficeSale(any(), any(), any());
        assertEquals(1.0, agentSales("sold_online"));
    }

    @Test
    void anAgentsAnswer_neverCarriesAWholeTicketNumber_aQr_orTheBuyersLink() {
        // A sales agent is neither the buyer, the holder nor the gate: the
        // tickets they sold go to the buyer's phone, never stay on the agent's.
        sellerAccess(allowed(null, null));
        BookingResponseDTO held = held();
        BookingResponseDTO sold = BookingResponseDTO.builder().id(held.getId())
                .viewToken("3f9c2a7e1b4d4e8fa0c6d2b9e5f71a34")
                .items(new java.util.ArrayList<>(java.util.List.of(com.innbucks.bookingservice.dto.BookingItemDTO
                        .builder().ticketNumber("20261120-7KQ4M-9XZ2B").qrCode("data:image/png;base64,AAAA")
                        .bookingItemId(UUID.randomUUID()).build())))
                .build();
        when(bookings.confirmBoxOfficeSale(held.getId(), AGENT_ACCOUNT, "Tendai Moyo")).thenReturn(sold);

        BookingResponseDTO answer = service().sell(request(), null, "+263771234567", appAgent());

        assertNull(answer.getViewToken());
        assertEquals("20261120-*****-**Z2B", answer.getItems().get(0).getTicketNumber());
        assertNull(answer.getItems().get(0).getQrCode());
        // No id that opens a ticket page while the cell's id links are open.
        assertNull(answer.getItems().get(0).getBookingItemId());
        assertNull(answer.getId());
    }

    @Test
    void anAgentsOnlineSale_keepsItsBookingId_forThePayment_butNoTicketIds() {
        BookingResponseDTO held = BookingResponseDTO.builder().id(UUID.randomUUID())
                .items(new java.util.ArrayList<>(java.util.List.of(com.innbucks.bookingservice.dto.BookingItemDTO
                        .builder().bookingItemId(UUID.randomUUID()).build())))
                .build();
        BookingResponseDTO answer = BoxOfficeService.withoutEntryCredentials(held, true);

        assertEquals(held.getId(), answer.getId());
        assertNull(answer.getItems().get(0).getBookingItemId());
    }

    @Test
    void theOrganizersAnswer_isLeftAsTheBoxOfficeAlwaysAnsweredIt() {
        window(EventSalesWindowDTO.ON_SALE, ORGANIZER);
        BookingResponseDTO held = held();
        BookingResponseDTO sold = BookingResponseDTO.builder().id(held.getId())
                .items(java.util.List.of(com.innbucks.bookingservice.dto.BookingItemDTO.builder()
                        .ticketNumber("20261120-7KQ4M-9XZ2B").build()))
                .build();
        when(bookings.confirmBoxOfficeSale(eq(held.getId()), any(), any())).thenReturn(sold);

        BookingResponseDTO answer = service().sell(request(), null, "+263771234567", organizer());

        assertEquals("20261120-7KQ4M-9XZ2B", answer.getItems().get(0).getTicketNumber(),
                "the cell's masking switch decides for the organizer, as before");
    }

    @Test
    void anOrganizerOfAnotherEvent_isAskedAboutAsAnAgent() {
        window(EventSalesWindowDTO.ON_SALE, UUID.randomUUID());
        when(events.sellerAccess(eq(ORGANIZER), eq(EVENT), anyString()))
                .thenReturn(ApiResult.ok("Seller access", SellerAccessDTO.builder().allowed(false).build()));

        ApiProblemException refused = assertThrows(ApiProblemException.class,
                () -> service().sell(request(), null, "+263771234567", organizer()));

        assertEquals(HttpStatus.FORBIDDEN, refused.getStatus());
        assertEquals("NOT_A_SALES_AGENT", refused.getErrorCode());
        assertEquals("You can't sell tickets for this event.", refused.getMessage());
        verify(bookings, never()).createBoxOfficeBooking(any(), any(), any(), any(), anyBoolean());
        assertEquals(1.0, agentSales("not_a_sales_agent"));
    }

    @Test
    void aPromoCode_onAnAgentsSale_isRefusedBeforeAnythingIsAsked() {
        BoxOfficeSaleRequestDTO withCode = request();
        withCode.setPromoCode(" early10 ");

        ApiProblemException refused = assertThrows(ApiProblemException.class,
                () -> service().sell(withCode, null, "+263771234567", appAgent()));

        assertEquals(HttpStatus.BAD_REQUEST, refused.getStatus());
        assertEquals("PROMO_CODE_NOT_ALLOWED", refused.getErrorCode());
        verifyNoInteractions(events);
        verify(bookings, never()).createBoxOfficeBooking(any(), any(), any(), any(), anyBoolean());
        assertEquals(1.0, agentSales("promo_code_refused"));
    }

    @Test
    void aBlankPromoCode_isNoPromoCode() {
        sellerAccess(allowed(null, "0"));
        BookingResponseDTO held = held();
        when(bookings.confirmBoxOfficeSale(any(), any(), any())).thenReturn(held);
        BoxOfficeSaleRequestDTO blank = request();
        blank.setPromoCode("  ");

        assertNotNull(service().sell(blank, null, "+263771234567", appAgent()));
    }

    @Test
    void anUnansweredOrNonsensicalSellerAccess_isA503_andSellsNothing() {
        when(events.sellerAccess(any(), any(), anyString())).thenReturn(ApiResult.ok("down", null));
        ApiProblemException down = assertThrows(ApiProblemException.class,
                () -> service().sell(request(), null, "+263771234567", appAgent()));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, down.getStatus());
        assertEquals("TRY_AGAIN", down.getErrorCode());

        when(events.sellerAccess(any(), any(), anyString())).thenThrow(new IllegalStateException("timeout"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertThrows(ApiProblemException.class,
                () -> service().sell(request(), null, "+263771234567", appAgent())).getStatus());

        reset(events);
        sellerAccess(SellerAccessDTO.builder().allowed(true).organizerUuid(ORGANIZER).build());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertThrows(ApiProblemException.class,
                () -> service().sell(request(), null, "+263771234567", appAgent())).getStatus(),
                "allowed with no agent row is not an answer to sell on");

        sellerAccess(allowed(null, "100.01"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertThrows(ApiProblemException.class,
                () -> service().sell(request(), null, "+263771234567", appAgent())).getStatus(),
                "a commission over 100% is not an answer to sell on");

        verify(bookings, never()).createBoxOfficeBooking(any(), any(), any(), any(), anyBoolean());
        assertEquals(4.0, agentSales("unavailable"));
    }

    @Test
    void aSessionWithNoAccount_isNotAnAgent() {
        BoxOfficeService.Caller anonymousish = new BoxOfficeService.Caller(null, null, false, null);

        ApiProblemException refused = assertThrows(ApiProblemException.class,
                () -> service().sell(request(), null, "+263771234567", anonymousish));

        assertEquals("NOT_A_SALES_AGENT", refused.getErrorCode());
        verifyNoInteractions(events);
    }

    @Test
    void anUnnamedAgent_isCalledSalesAgent() {
        sellerAccess(SellerAccessDTO.builder().allowed(true).agentId(AGENT_ROW).organizerUuid(ORGANIZER)
                .displayName("  ").build());
        held();

        service().sell(online(), null, "+263771234567", appAgent());

        ArgumentCaptor<BoxOfficeSeller> seller = ArgumentCaptor.forClass(BoxOfficeSeller.class);
        verify(bookings).createBoxOfficeBooking(any(), any(), any(), seller.capture(), anyBoolean());
        assertEquals("Sales agent", seller.getValue().name());
    }

    // ---- voids -------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void aVoid_asksTheEventsStartLive_andAnUnansweredOneIsA503() {
        UUID booking = UUID.randomUUID();
        BoxOfficeService service = service();
        service.voidSale(booking, "Buyer changed their mind", ORGANIZER, ORGANIZER, "o");
        ArgumentCaptor<Function<UUID, LocalDateTime>> start = ArgumentCaptor.forClass(Function.class);
        verify(bookings).voidCashSale(eq(booking), eq(ORGANIZER), eq(ORGANIZER), eq("o"),
                eq("Buyer changed their mind"), start.capture());

        LocalDateTime at = LocalDateTime.of(2030, 11, 20, 8, 0);
        when(events.getEventInternal(eq(EVENT), anyString())).thenReturn(ApiResult.ok("ok",
                EventLookupDTO.builder().eventId(EVENT).startDateTime(at).build()));
        assertEquals(at, start.getValue().apply(EVENT));

        when(events.getEventInternal(eq(EVENT), anyString())).thenReturn(ApiResult.ok("down", null));
        assertEquals("TRY_AGAIN", assertThrows(ApiProblemException.class,
                () -> start.getValue().apply(EVENT)).getErrorCode());
    }

    @Test
    void everyAgentSaleOutcome_isRegisteredAtZero() {
        service();
        for (BookingMetrics.AgentSaleOutcome outcome : BookingMetrics.AgentSaleOutcome.values()) {
            assertEquals(0.0, agentSales(outcome.tag()), outcome.tag());
        }
    }
}
