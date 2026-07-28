package com.innbucks.userservice.service;

import com.innbucks.userservice.config.FineractGatewayProperties;
import com.innbucks.userservice.dto.FineractSmsDeliveryReportDTO;
import com.innbucks.userservice.dto.FineractSmsQueueItemDTO;
import com.innbucks.userservice.entity.FineractGatewayMessage;
import com.innbucks.userservice.repository.FineractGatewayMessageRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Pins the store-side rules of the Fineract Message Gateway facade:
 * idempotent accept (an OTP must never double-send on a Fineract batch
 * retry), provider→channel routing, INVALID capture for unsendable items,
 * and the delivery-report quirks Fineract's tasklet forces on us
 * ({@code hasError} always false, unknown ids answered terminally).
 */
class FineractGatewayMessageServiceTest {

    private final FineractGatewayMessageRepository repository = mock(FineractGatewayMessageRepository.class);

    private FineractGatewayMessageService service() {
        FineractGatewayProperties props = new FineractGatewayProperties();
        props.setWhatsappProviderIds(List.of(2L));
        // saves get an id assigned, like JPA would
        AtomicLong seq = new AtomicLong(100);
        when(repository.save(any())).thenAnswer(inv -> {
            FineractGatewayMessage row = inv.getArgument(0);
            if (row.getId() == null) row.setId(seq.incrementAndGet());
            return row;
        });
        return new FineractGatewayMessageService(repository, props);
    }

    private static FineractSmsQueueItemDTO item(Long internalId, String mobile, String message, Long providerId) {
        return FineractSmsQueueItemDTO.builder()
                .internalId(internalId).mobileNumber(mobile).message(message).providerId(providerId)
                .build();
    }

    private static FineractGatewayMessage existing(long fineractId, int status) {
        return FineractGatewayMessage.builder()
                .id(7L).fineractId(fineractId).tenantId("default").mobileNumber("+263771234567")
                .channel("SMS").deliveryStatus(status).externalRef("FIN-GW-x")
                .createdAt(Instant.parse("2026-07-28T08:00:00Z"))
                .build();
    }

    // ---- accept ----------------------------------------------------------

    @Test
    void newItem_isStoredPending_andQueuedForDispatch() {
        when(repository.findByTenantIdAndFineractId("default", 1L)).thenReturn(Optional.empty());

        List<Long> toDispatch = service().acceptBatch("default",
                List.of(item(1L, "+263771234567", "hello", 1L)));

        ArgumentCaptor<FineractGatewayMessage> saved = ArgumentCaptor.forClass(FineractGatewayMessage.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getDeliveryStatus()).isEqualTo(FineractGatewayMessage.STATUS_PENDING);
        assertThat(saved.getValue().getChannel()).isEqualTo("SMS");
        assertThat(saved.getValue().getMessage()).isEqualTo("hello");
        assertThat(saved.getValue().getExternalRef()).startsWith("FIN-GW-");
        assertThat(toDispatch).containsExactly(saved.getValue().getId());
    }

    @Test
    void whatsappProviderId_routesToWhatsappChannel() {
        when(repository.findByTenantIdAndFineractId(any(), any())).thenReturn(Optional.empty());

        service().acceptBatch("default", List.of(item(1L, "+263771234567", "hi", 2L)));

        ArgumentCaptor<FineractGatewayMessage> saved = ArgumentCaptor.forClass(FineractGatewayMessage.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getChannel()).isEqualTo("WHATSAPP");
    }

    @Test
    void nullProviderId_defaultsToSmsChannel() {
        // Fineract's triggered sends (2FA OTP) carry the configured default
        // provider, but a null must still land somewhere safe: SMS.
        FineractGatewayMessageService service = service();
        assertThat(service.resolveChannel(null)).isEqualTo("SMS");
        assertThat(service.resolveChannel(1L)).isEqualTo("SMS");
        assertThat(service.resolveChannel(2L)).isEqualTo("WHATSAPP");
    }

    @Test
    void blankMobile_isStoredInvalid_andNeverDispatched() {
        when(repository.findByTenantIdAndFineractId(any(), any())).thenReturn(Optional.empty());

        List<Long> toDispatch = service().acceptBatch("default",
                List.of(item(1L, "  ", "hello", 1L)));

        ArgumentCaptor<FineractGatewayMessage> saved = ArgumentCaptor.forClass(FineractGatewayMessage.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getDeliveryStatus()).isEqualTo(FineractGatewayMessage.STATUS_INVALID);
        assertThat(saved.getValue().getMessage()).isNull(); // nothing sendable is retained
        assertThat(toDispatch).isEmpty();
    }

    @Test
    void blankMessage_isStoredInvalid_andNeverDispatched() {
        when(repository.findByTenantIdAndFineractId(any(), any())).thenReturn(Optional.empty());

        List<Long> toDispatch = service().acceptBatch("default",
                List.of(item(1L, "+263771234567", null, 1L)));

        ArgumentCaptor<FineractGatewayMessage> saved = ArgumentCaptor.forClass(FineractGatewayMessage.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getDeliveryStatus()).isEqualTo(FineractGatewayMessage.STATUS_INVALID);
        assertThat(toDispatch).isEmpty();
    }

    @Test
    void missingInternalId_isDropped_notStored() {
        List<Long> toDispatch = service().acceptBatch("default",
                List.of(item(null, "+263771234567", "hello", 1L)));

        verify(repository, never()).save(any());
        assertThat(toDispatch).isEmpty();
    }

    @Test
    void duplicateOfSentRow_isSkipped_notResent() {
        // Fineract re-POSTs a whole batch when it never saw our 202; a row we
        // already delivered (it may carry an OTP) must not go out again.
        when(repository.findByTenantIdAndFineractId("default", 5L))
                .thenReturn(Optional.of(existing(5L, FineractGatewayMessage.STATUS_SENT)));

        List<Long> toDispatch = service().acceptBatch("default",
                List.of(item(5L, "+263771234567", "hello", 1L)));

        verify(repository, never()).save(any());
        assertThat(toDispatch).isEmpty();
    }

    @Test
    void duplicateOfPendingRow_isSkipped() {
        when(repository.findByTenantIdAndFineractId("default", 5L))
                .thenReturn(Optional.of(existing(5L, FineractGatewayMessage.STATUS_PENDING)));

        assertThat(service().acceptBatch("default",
                List.of(item(5L, "+263771234567", "hello", 1L)))).isEmpty();
    }

    @Test
    void duplicateOfFailedRow_isRequeuedWithFreshPayload() {
        FineractGatewayMessage failed = existing(5L, FineractGatewayMessage.STATUS_FAILED);
        failed.setErrorMessage("gateway down");
        when(repository.findByTenantIdAndFineractId("default", 5L)).thenReturn(Optional.of(failed));

        List<Long> toDispatch = service().acceptBatch("default",
                List.of(item(5L, "+263779999999", "retry text", 2L)));

        assertThat(toDispatch).containsExactly(7L);
        assertThat(failed.getDeliveryStatus()).isEqualTo(FineractGatewayMessage.STATUS_PENDING);
        assertThat(failed.getMobileNumber()).isEqualTo("+263779999999");
        assertThat(failed.getMessage()).isEqualTo("retry text");
        assertThat(failed.getChannel()).isEqualTo("WHATSAPP");
        assertThat(failed.getErrorMessage()).isNull();
    }

    // ---- reports ---------------------------------------------------------

    @Test
    void sentRow_reportsStatus200_withExternalRef_andHasErrorFalse() {
        FineractGatewayMessage sent = existing(42L, FineractGatewayMessage.STATUS_SENT);
        sent.setSentAt(Instant.parse("2026-07-28T08:05:00Z"));
        when(repository.findByTenantIdAndFineractIdIn("default", List.of(42L)))
                .thenReturn(List.of(sent));

        List<FineractSmsDeliveryReportDTO> reports = service().buildReports("default", List.of(42L));

        assertThat(reports).hasSize(1);
        FineractSmsDeliveryReportDTO report = reports.get(0);
        assertThat(report.getId()).isEqualTo(42L); // FINERACT's id, not our row id
        assertThat(report.getExternalId()).isEqualTo("FIN-GW-x");
        assertThat(report.getDeliveryStatus()).isEqualTo(200);
        assertThat(report.getHasError()).isFalse();
        assertThat(report.getDeliveredOnDate()).contains("2026-07-28");
    }

    @Test
    void failedRow_reportsStatus400_butHasErrorStaysFalse() {
        // Fineract's tasklet SKIPS any report row with hasError=true — a
        // failure only flips the Fineract row to FAILED when it rides
        // deliveryStatus=400 with hasError=false.
        FineractGatewayMessage failed = existing(42L, FineractGatewayMessage.STATUS_FAILED);
        failed.setErrorMessage("WhatsApp gateway is unreachable");
        when(repository.findByTenantIdAndFineractIdIn("default", List.of(42L)))
                .thenReturn(List.of(failed));

        List<FineractSmsDeliveryReportDTO> reports = service().buildReports("default", List.of(42L));

        assertThat(reports.get(0).getDeliveryStatus()).isEqualTo(400);
        assertThat(reports.get(0).getHasError()).isFalse();
        assertThat(reports.get(0).getErrorMessage()).isEqualTo("WhatsApp gateway is unreachable");
    }

    @Test
    void unknownId_isAnsweredTerminallyFailed_soFineractStopsPolling() {
        when(repository.findByTenantIdAndFineractIdIn("default", List.of(99L)))
                .thenReturn(List.of());

        List<FineractSmsDeliveryReportDTO> reports = service().buildReports("default", List.of(99L));

        assertThat(reports).hasSize(1);
        assertThat(reports.get(0).getId()).isEqualTo(99L);
        assertThat(reports.get(0).getDeliveryStatus()).isEqualTo(400);
        assertThat(reports.get(0).getHasError()).isFalse();
        assertThat(reports.get(0).getErrorMessage()).contains("never received");
    }
}
