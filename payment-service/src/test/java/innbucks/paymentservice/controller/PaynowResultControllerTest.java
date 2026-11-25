package innbucks.paymentservice.controller;

import innbucks.paymentservice.service.PaynowPaymentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Paynow's result URL answers 200 unless the payment could not be checked, so Paynow resends only then. */
class PaynowResultControllerTest {

    private final PaynowPaymentService service = mock(PaynowPaymentService.class);
    private final PaynowResultController controller = new PaynowResultController(service);

    @Test
    @DisplayName("handled or ignored: 200; could not check: 503; an exception: 503")
    void acks() {
        when(service.onResultUpdate("reference=R&status=Paid")).thenReturn(PaynowPaymentService.UpdateAck.OK);
        assertThat(controller.result("reference=R&status=Paid", new MockHttpServletRequest()).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        when(service.onResultUpdate("reference=R2")).thenReturn(PaynowPaymentService.UpdateAck.RETRY);
        assertThat(controller.result("reference=R2", new MockHttpServletRequest()).getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        when(service.onResultUpdate("reference=R3")).thenThrow(new IllegalStateException("boom"));
        assertThat(controller.result("reference=R3", new MockHttpServletRequest()).getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("a form already parsed upstream is rebuilt in the order it arrived (the hash depends on it)")
    void rebuiltFromParameters() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addParameter("reference", "TKZ-A-1");
        request.addParameter("status", "Awaiting Delivery");
        request.addParameter("hash", "AB");
        when(service.onResultUpdate(anyString())).thenReturn(PaynowPaymentService.UpdateAck.OK);

        controller.result(null, request);

        verify(service).onResultUpdate("reference=TKZ-A-1&status=Awaiting+Delivery&hash=AB");
        Map<String, String[]> ordered = new LinkedHashMap<>();
        ordered.put("b", new String[]{"2"});
        ordered.put("a", new String[]{"1"});
        assertThat(PaynowResultController.fromParameters(ordered)).isEqualTo("b=2&a=1");
    }
}
