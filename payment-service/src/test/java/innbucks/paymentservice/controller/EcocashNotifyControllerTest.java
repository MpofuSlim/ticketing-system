package innbucks.paymentservice.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import innbucks.paymentservice.entity.Payment;
import innbucks.paymentservice.entity.PaymentRail;
import innbucks.paymentservice.repository.PaymentRepository;
import innbucks.paymentservice.service.EcocashPaymentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The notify webhook's one security property, pinned: it is a TRIGGER, never
 * a truth source. Whatever the body claims, the only action ever taken is a
 * fresh upstream Query through the shared resolver — and the response is a
 * constant 200 regardless of what matched, so a prober learns nothing.
 */
class EcocashNotifyControllerTest {

    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final EcocashPaymentService service = mock(EcocashPaymentService.class);
    private final EcocashNotifyController controller =
            new EcocashNotifyController(payments, service, new ObjectMapper());

    private static Payment openRow(String correlator) {
        return Payment.builder()
                .id(UUID.randomUUID())
                .paymentReference("TKZ-PINKRUN26-4F3A2B1C0D9E")
                .paymentRail(PaymentRail.ECOCASH)
                .status(Payment.PaymentStatus.TOKEN_ISSUED)
                .ecocashClientCorrelator(correlator)
                .build();
    }

    @Test
    @DisplayName("a notify for an open row triggers the shared resolver — and NOTHING from the body is trusted")
    void notify_triggersResolver_neverTrustsBody() {
        Payment p = openRow("1763385010123456");
        when(payments.findByEcocashClientCorrelator("1763385010123456")).thenReturn(Optional.of(p));

        // The body CLAIMS completed — if the controller trusted it, it would
        // transition the row itself. It must only trigger the Query resolver.
        var resp = controller.notify("""
                {"clientCorrelator":"1763385010123456",
                 "transactionOperationStatus":"COMPLETED",
                 "paymentAmount":{"totalAmountCharged":9999.0}}""");

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        verify(service).resolveOpenCharge(p);
    }

    @Test
    @DisplayName("unknown correlator: constant 200, nothing resolved (enumeration resistance)")
    void notify_unknownCorrelator_constant200() {
        when(payments.findByEcocashClientCorrelator(anyString())).thenReturn(Optional.empty());

        var resp = controller.notify("{\"clientCorrelator\":\"9999999999999999\"}");

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        verify(service, never()).resolveOpenCharge(any());
    }

    @Test
    @DisplayName("closed rows are not re-resolved")
    void notify_terminalRowIgnored() {
        Payment p = openRow("1763385010123456");
        p.setStatus(Payment.PaymentStatus.SUCCEEDED);
        when(payments.findByEcocashClientCorrelator("1763385010123456")).thenReturn(Optional.of(p));

        var resp = controller.notify("{\"clientCorrelator\":\"1763385010123456\"}");

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        verify(service, never()).resolveOpenCharge(any());
    }

    @Test
    @DisplayName("garbage / empty / numeric-correlator bodies all answer a constant 200")
    void notify_garbageBodies_constant200() {
        assertThat(controller.notify(null).getStatusCode().value()).isEqualTo(200);
        assertThat(controller.notify("").getStatusCode().value()).isEqualTo(200);
        assertThat(controller.notify("<html>not json</html>").getStatusCode().value()).isEqualTo(200);
        assertThat(controller.notify("{\"noCorrelator\":true}").getStatusCode().value()).isEqualTo(200);

        // Numeric correlator (the PDF samples send numbers, not strings).
        when(payments.findByEcocashClientCorrelator("1763385010123456"))
                .thenReturn(Optional.of(openRow("1763385010123456")));
        assertThat(controller.notify("{\"clientCorrelator\":1763385010123456}")
                .getStatusCode().value()).isEqualTo(200);
        verify(service).resolveOpenCharge(any());
    }

    @Test
    @DisplayName("a resolver failure never leaks to the caller — still 200; the poller retries")
    void notify_resolverFailureStill200() {
        Payment p = openRow("1763385010123456");
        when(payments.findByEcocashClientCorrelator("1763385010123456")).thenReturn(Optional.of(p));
        when(service.resolveOpenCharge(p)).thenThrow(new RuntimeException("gateway down"));

        assertThat(controller.notify("{\"clientCorrelator\":\"1763385010123456\"}")
                .getStatusCode().value()).isEqualTo(200);
    }
}
