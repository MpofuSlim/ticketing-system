package com.innbucks.bookingservice.exception;

import com.innbucks.bookingservice.client.UserServiceClient;
import com.innbucks.bookingservice.controller.BookingController;
import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.service.BookingService;
import com.innbucks.bookingservice.service.EventChangeNotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The new handlers through REAL Spring MVC dispatch, not by calling the
 * handler methods: the defect they fix was never in a handler body but in
 * WHICH handler Spring picked. {@code HttpMessageNotReadableException},
 * {@code MethodArgumentTypeMismatchException} and the optimistic-lock failure
 * all extend RuntimeException, so the catch-all answered every one of them
 * 500. Each case below fails with {@code 500} the moment its handler goes.
 */
class GlobalExceptionHandlerDispatchTest {

    private static final String INTERNAL_TOKEN = "dispatch-test-internal-token";

    private final BookingService bookingService = mock(BookingService.class);
    private MockMvc mvc;

    @RestController
    static class ProbeController {

        record Seat(UUID categoryId, Integer quantity) {
        }

        record Body(UUID eventId, List<Seat> seats, Booking.BookingStatus status) {
        }

        @PostMapping("/probe")
        String post(@RequestBody Body body) {
            return "ok";
        }

        @GetMapping("/probe/{id}")
        String byId(@PathVariable UUID id, @RequestParam(required = false) Integer page) {
            return "ok";
        }

        @GetMapping("/probe/lost-race")
        String lostRace() {
            throw new ObjectOptimisticLockingFailureException(Booking.class, UUID.randomUUID());
        }

        @GetMapping("/probe/lost-race-untranslated")
        String lostRaceUntranslated() {
            throw new jakarta.persistence.OptimisticLockException("Row was updated or deleted by another transaction");
        }

        @GetMapping("/probe/problem")
        String problem() {
            throw new ApiProblemException(HttpStatus.SERVICE_UNAVAILABLE, "TRY_AGAIN",
                    "We couldn't reserve your tickets just now. Please try again.",
                    Map.of("retryAfterSeconds", 2));
        }

        @GetMapping("/probe/boom")
        String boom() {
            throw new IllegalStateException("internal detail");
        }
    }

    @BeforeEach
    void setUp() {
        BookingController bookings = new BookingController(bookingService, mock(UserServiceClient.class),
                mock(EventChangeNotificationService.class));
        ReflectionTestUtils.setField(bookings, "expectedInternalToken", INTERNAL_TOKEN);
        mvc = MockMvcBuilders.standaloneSetup(new ProbeController(), bookings)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    // ---- unreadable body: 400 keyed by the Jackson path ---------------------

    @Test
    void aMalformedIdInsideAList_is400_keyedByItsJacksonPath() throws Exception {
        mvc.perform(post("/probe").contentType(MediaType.APPLICATION_JSON).content("""
                        {"eventId":"%s","seats":[{"categoryId":"%s"},{"categoryId":"c-vip"}]}
                        """.formatted(UUID.randomUUID(), UUID.randomUUID())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("400 BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.data['seats[1].categoryId']").value("Must be a valid UUID."));
    }

    @Test
    void aWordWhereANumberGoes_andAnUnknownEnum_sayWhatIsExpected() throws Exception {
        mvc.perform(post("/probe").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seats\":[{\"quantity\":\"two\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data['seats[0].quantity']").value("Must be a number."));

        mvc.perform(post("/probe").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PAID\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.status").value("Must be one of: PENDING, CONFIRMED, CANCELLED."));
    }

    @Test
    void brokenJson_andAMissingBody_areKeyedOnTheBody_andNeverEchoTheInput() throws Exception {
        mvc.perform(post("/probe").contentType(MediaType.APPLICATION_JSON).content("{\"eventId\": +263771234567"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.data.body").value("The request body is not valid JSON."))
                .andExpect(result -> org.assertj.core.api.Assertions
                        .assertThat(result.getResponse().getContentAsString()).doesNotContain("263771234567"));

        mvc.perform(post("/probe").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.body").value("A request body is required."));
    }

    @Test
    void wellFormedJsonOfTheWrongKind_isNotCalledInvalidJson() throws Exception {
        // Valid JSON, but not the object the endpoint takes: the client has a
        // shape to fix, not a syntax error. The value is never echoed.
        for (String body : List.of("[]", "[{\"eventId\":\"%s\"}]".formatted(UUID.randomUUID()),
                "\"+263771234567\"", "263771234567", "true",
                // A second value after the object (Jackson 3 refuses trailing tokens).
                "{\"eventId\":\"%s\"} {\"phone\":\"+263771234567\"}".formatted(UUID.randomUUID()))) {
            mvc.perform(post("/probe").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Validation failed"))
                    .andExpect(jsonPath("$.data.body").value("The request body must be a single JSON object."))
                    .andExpect(result -> org.assertj.core.api.Assertions
                            .assertThat(result.getResponse().getContentAsString()).doesNotContain("263771234567"));
        }
    }

    @Test
    void theRealConfirm_anArrayBody_isAShapeError_beforeTheServiceIsReached() throws Exception {
        mvc.perform(patch("/bookings/internal/" + UUID.randomUUID() + "/confirm")
                        .header("X-Internal-Token", "any").contentType(MediaType.APPLICATION_JSON).content("[]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.body").value("The request body must be a single JSON object."));
        verifyNoInteractions(bookingService);
    }

    @Test
    void theRealCreateBooking_badCategoryId_is400_beforeTheServiceIsReached() throws Exception {
        mvc.perform(post("/bookings").contentType(MediaType.APPLICATION_JSON).content("""
                        {"eventId":"%s","customerName":"Rufaro Moyo","phoneNumber":"+263771234567",
                         "seats":[{"categoryId":"c-vip"}]}
                        """.formatted(UUID.randomUUID())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data['seats[0].categoryId']").value("Must be a valid UUID."));
        verifyNoInteractions(bookingService);
    }

    // ---- path variable / query parameter: 400 keyed by the parameter ---------

    @Test
    void aMalformedPathVariableOrQueryParameter_is400_keyedByItsName() throws Exception {
        mvc.perform(get("/probe/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.data.id").value("Must be a valid UUID."));

        mvc.perform(get("/probe/" + UUID.randomUUID()).param("page", "first"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.page").value("Must be a number."));
    }

    @Test
    void theRealCancel_badBookingId_is400() throws Exception {
        mvc.perform(patch("/bookings/not-a-uuid/cancel"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.id").value("Must be a valid UUID."));
        verifyNoInteractions(bookingService);
    }

    // ---- lost race: 409 with a domain code ------------------------------------

    @Test
    void anOptimisticLockFailure_is409_withCONCURRENT_UPDATE() throws Exception {
        mvc.perform(get("/probe/lost-race"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("409 CONFLICT"))
                .andExpect(jsonPath("$.data.errorCode").value("CONCURRENT_UPDATE"));

        // The raw JPA exception, as an auto-flush raises it, is the same 409.
        mvc.perform(get("/probe/lost-race-untranslated"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.errorCode").value("CONCURRENT_UPDATE"));
    }

    @Test
    void theRealConfirm_losingARace_isTheGlobal409_notTheOldControllerLocalBody() throws Exception {
        // Confirm's own catch is gone: under the row lock the race cannot
        // happen, and if a lost race ever surfaces it gets the one shape every
        // endpoint uses, not "Booking already confirmed by a concurrent request".
        when(bookingService.confirmBooking(any(), any()))
                .thenThrow(new jakarta.persistence.OptimisticLockException("lost"));
        mvc.perform(patch("/bookings/internal/" + UUID.randomUUID() + "/confirm")
                        .header("X-Internal-Token", INTERNAL_TOKEN))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.errorCode").value("CONCURRENT_UPDATE"))
                .andExpect(jsonPath("$.message").value(
                        "This booking was changed by another request at the same time. Please refresh it and try again."));
    }

    @Test
    void theRealReverse_losingTheVersionRace_is409_notA500() throws Exception {
        when(bookingService.reverseConfirmedBooking(any(), anyString()))
                .thenThrow(new ObjectOptimisticLockingFailureException(Booking.class, UUID.randomUUID()));
        mvc.perform(patch("/bookings/" + UUID.randomUUID() + "/reverse")
                        .principal(new org.springframework.security.authentication.TestingAuthenticationToken(
                                "admin@innbucks.co.zw", null, "ROLE_SUPER_ADMIN")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.errorCode").value("CONCURRENT_UPDATE"));
    }

    // ---- ApiProblemException ------------------------------------------------

    @Test
    void anApiProblem_rendersItsStatus_message_errorCode_andExtra() throws Exception {
        mvc.perform(get("/probe/problem"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("503 SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("We couldn't reserve your tickets just now. Please try again."))
                .andExpect(jsonPath("$.data.errorCode").value("TRY_AGAIN"))
                .andExpect(jsonPath("$.data.retryAfterSeconds").value(2));
    }

    @Test
    void theCatchAllStillAnswers500_forAGenuineServerFault() throws Exception {
        mvc.perform(get("/probe/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("An unexpected error occurred. Please try again."));
    }

    @Test
    void cancelStillReachesTheServiceForAWellFormedId() throws Exception {
        when(bookingService.cancelBooking(any(), any(), anyBoolean()))
                .thenThrow(new BookingConflictException("This booking is already cancelled."));
        mvc.perform(patch("/bookings/" + UUID.randomUUID() + "/cancel")
                        .principal(new org.springframework.security.authentication.TestingAuthenticationToken(
                                "alice@example.com", null, "ROLE_CUSTOMER")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("This booking is already cancelled."));
    }
}
