package innbucks.paymentservice.exception;

import innbucks.paymentservice.dto.PaymentOtpRequest;
import innbucks.paymentservice.dto.PaymentRequest;
import jakarta.validation.Valid;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spring MVC's own request errors through REAL dispatch, not by calling the
 * handler methods: the defect was never in a handler body but in WHICH handler
 * Spring picked. Each of these used to reach the {@code Exception} catch-all
 * and answer 500 "internal error". Every case fails with 500 the moment its
 * handler goes. The bodies are the real {@link PaymentRequest} and
 * {@link PaymentOtpRequest}, so the cases are what a website can send.
 */
class GlobalExceptionHandlerDispatchTest {

    private MockMvc mvc;

    @RestController
    static class ProbeController {

        @PostMapping("/payments")
        String pay(@RequestBody PaymentRequest body) {
            return "ok";
        }

        @PostMapping("/payments/{transactionId}/otp")
        String otp(@PathVariable String transactionId, @Valid @RequestBody PaymentOtpRequest body) {
            return "ok";
        }

        @GetMapping("/probe/{id}")
        String byId(@PathVariable UUID id) {
            return "ok";
        }

        @GetMapping("/probe")
        String page(@RequestParam int page) {
            return "ok";
        }

        @GetMapping("/probe/header")
        String withHeader(@RequestHeader("X-Internal-Token") String token) {
            return "ok";
        }
    }

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void aBookingIdThatIsNotAUuid_is400_keyedByTheField() throws Exception {
        mvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookingId\":\"not-a-uuid\",\"paymentRail\":\"PAYNOW_CARD\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("400 BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("validation failed"))
                .andExpect(jsonPath("$.data.bookingId").value("Must be a valid UUID."));
    }

    @Test
    void aPaymentRailThatIsNotOneOfOurs_is400_namingTheValues() throws Exception {
        for (String rail : new String[] {"PAYNOW", "onemoney"}) {
            mvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"bookingId\":\"" + UUID.randomUUID() + "\",\"paymentRail\":\"" + rail + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.data.paymentRail", startsWith("Must be one of: ")))
                    .andExpect(jsonPath("$.data.paymentRail", containsString("ONEMONEY")))
                    .andExpect(jsonPath("$.data.paymentRail", containsString("PAYNOW_CARD")));
        }
    }

    @Test
    void aBodyThatIsNotJson_is400() throws Exception {
        mvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON).content("{bookingId:"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.body").value("The request body is not valid JSON."));
    }

    @Test
    void noBody_is400() throws Exception {
        mvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.body").value("A request body is required."));
    }

    @Test
    void aJsonArrayInsteadOfAnObject_is400() throws Exception {
        mvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON).content("[]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.body").value("The request body must be a single JSON object."));
    }

    @Test
    void noContentType_is415() throws Exception {
        mvc.perform(post("/payments").content("{\"bookingId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("415 UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void theOtpBodyStillAnswersBeanValidationAsBefore() throws Exception {
        mvc.perform(post("/payments/abc/otp").contentType(MediaType.APPLICATION_JSON).content("{\"otp\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("validation failed"))
                .andExpect(jsonPath("$.data.otp").exists());
    }

    @Test
    void aPathVariableThatIsNotAUuid_is400() throws Exception {
        mvc.perform(get("/probe/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.id").value("Must be a valid UUID."));
    }

    @Test
    void aMissingOrMistypedQueryParameter_is400() throws Exception {
        mvc.perform(get("/probe"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.page").value("Required."));
        mvc.perform(get("/probe").param("page", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.page").value("Must be a number."));
    }

    @Test
    void aMissingRequiredHeader_is400() throws Exception {
        mvc.perform(get("/probe/header"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data['X-Internal-Token']").value("Required."));
    }

    @Test
    void theWrongVerb_is405_withAllow() throws Exception {
        mvc.perform(delete("/payments"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", containsString("POST")))
                .andExpect(jsonPath("$.code").value("405 METHOD_NOT_ALLOWED"));
    }
}
