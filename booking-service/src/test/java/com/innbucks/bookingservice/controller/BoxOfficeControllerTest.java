package com.innbucks.bookingservice.controller;

import com.innbucks.bookingservice.dto.BookingResponseDTO;
import com.innbucks.bookingservice.dto.BoxOfficeSaleRequestDTO;
import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.exception.ApiProblemException;
import com.innbucks.bookingservice.exception.GlobalExceptionHandler;
import com.innbucks.bookingservice.security.JwtAuthDetails;
import com.innbucks.bookingservice.service.BookingService;
import com.innbucks.bookingservice.service.BoxOfficeService;
import com.innbucks.bookingservice.service.SalesAgentReportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The box office controller through Spring MVC dispatch (V36): the seller is
 * read from the session (role, account, organizer claim), the buyer from the
 * body only, the answer's message says whether the sale is paid, and the cap
 * refusal renders exactly its Swagger example.
 */
class BoxOfficeControllerTest {

    private static final UUID EVENT = UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6");
    private static final UUID CATEGORY = UUID.randomUUID();
    private static final UUID ORGANIZER = UUID.randomUUID();
    private static final UUID AGENT_ACCOUNT = UUID.randomUUID();

    private final BoxOfficeService boxOffice = mock(BoxOfficeService.class);
    private final SalesAgentReportService reports = mock(SalesAgentReportService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        BoxOfficeController controller = new BoxOfficeController(boxOffice, "ZW");
        controller.setSalesAgentReports(reports);
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    private static UsernamePasswordAuthenticationToken organizer() {
        var auth = new UsernamePasswordAuthenticationToken("organizer@example.com", null,
                List.of(new SimpleGrantedAuthority("ROLE_EVENT_ORGANIZER")));
        auth.setDetails(new JwtAuthDetails("organizer@example.com", null, ORGANIZER, ORGANIZER, "Tariro", "Chikomo"));
        return auth;
    }

    /** The InnBucks app: CUSTOMER, no email, no organizer claim. */
    private static UsernamePasswordAuthenticationToken appAgent() {
        var auth = new UsernamePasswordAuthenticationToken(null, null,
                List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
        auth.setDetails(new JwtAuthDetails(null, "+263772000111", AGENT_ACCOUNT, null, null, null));
        return auth;
    }

    private static MockHttpServletRequestBuilder sale(String paymentMethod, UsernamePasswordAuthenticationToken who) {
        return post("/bookings/box-office").principal(who).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"eventId":"%s","customerName":" Rufaro Moyo ","phoneNumber":"0771234567",%s
                         "seats":[{"categoryId":"%s"}]}""".formatted(EVENT,
                        paymentMethod == null ? "" : "\"paymentMethod\":\"" + paymentMethod + "\",", CATEGORY));
    }

    @Test
    void theOrganizersCashSale_isRecorded_withTheirSessionAsTheSeller() throws Exception {
        when(boxOffice.sell(any(), any(), any(), any())).thenReturn(BookingResponseDTO.builder()
                .status(Booking.BookingStatus.CONFIRMED).build());

        mvc.perform(sale(null, organizer()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("Cash sale recorded"));

        ArgumentCaptor<BoxOfficeService.Caller> caller = ArgumentCaptor.forClass(BoxOfficeService.Caller.class);
        ArgumentCaptor<BoxOfficeSaleRequestDTO> body = ArgumentCaptor.forClass(BoxOfficeSaleRequestDTO.class);
        verify(boxOffice).sell(body.capture(), eq(null), eq("+263771234567"), caller.capture());
        assertThat(caller.getValue()).isEqualTo(new BoxOfficeService.Caller(ORGANIZER, ORGANIZER, true,
                "organizer@example.com"));
        assertThat(body.getValue().getCustomerName()).isEqualTo("Rufaro Moyo");
        assertThat(body.getValue().paymentMethodOrDefault()).isEqualTo(BoxOfficeSaleRequestDTO.PaymentMethod.CASH);
    }

    @Test
    void anAppAgentsOnlineSale_isAwaitingPayment_andTheSellerIsTheAppAccount() throws Exception {
        when(boxOffice.sell(any(), any(), any(), any())).thenReturn(BookingResponseDTO.builder()
                .status(Booking.BookingStatus.PENDING).build());

        mvc.perform(sale("ONLINE", appAgent()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("Sale created, awaiting payment"))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.viewToken").doesNotExist());

        ArgumentCaptor<BoxOfficeService.Caller> caller = ArgumentCaptor.forClass(BoxOfficeService.Caller.class);
        verify(boxOffice).sell(any(), any(), any(), caller.capture());
        assertThat(caller.getValue()).isEqualTo(new BoxOfficeService.Caller(AGENT_ACCOUNT, null, false, null));
    }

    @Test
    void theCapRefusal_rendersExactlyItsExample() throws Exception {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("cap", 200);
        extra.put("remaining", 2L);
        when(boxOffice.sell(any(), any(), any(), any())).thenThrow(new ApiProblemException(HttpStatus.CONFLICT,
                BookingService.AGENT_CAP_REACHED, BookingService.agentCapMessage(200, 2), extra));

        mvc.perform(sale(null, appAgent()))
                .andExpect(status().isConflict())
                .andExpect(content().json(BoxOfficeController.EXAMPLE_AGENT_CAP_REACHED, JsonCompareMode.STRICT));
    }

    @Test
    void anUnknownPaymentMethod_isA400_andSellsNothing() throws Exception {
        mvc.perform(sale("CHEQUE", appAgent()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.paymentMethod").value("Must be one of: CASH, ONLINE."));
        verifyNoInteractions(boxOffice);
    }

    @Test
    void mySales_isTheSessionsOwn_andNeverCached() throws Exception {
        mvc.perform(get("/bookings/box-office/my-sales").principal(appAgent()).param("eventId", EVENT.toString()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.message").value("Your sales"));
        verify(reports).mySales(AGENT_ACCOUNT, EVENT, null, null);

        var noAccount = new UsernamePasswordAuthenticationToken(null, null,
                List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
        noAccount.setDetails(new JwtAuthDetails(null, "+263772000111", null, null, null, null));
        mvc.perform(get("/bookings/box-office/my-sales").principal(noAccount))
                .andExpect(status().isBadRequest());
    }
}
