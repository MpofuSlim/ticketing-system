package com.innbucks.userservice.service;

import com.innbucks.userservice.config.FineractGatewayProperties;
import com.innbucks.userservice.dto.FineractSmsDeliveryReportDTO;
import com.innbucks.userservice.dto.FineractSmsQueueItemDTO;
import com.innbucks.userservice.entity.FineractGatewayMessage;
import com.innbucks.userservice.repository.FineractGatewayMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Store side of the Fineract Message Gateway facade: accepts a queue batch
 * into {@code fineract_gateway_messages} (idempotently — Fineract re-POSTs a
 * whole batch when it never saw our 202, and this rail carries OTPs, so a
 * duplicate id must never double-send) and answers delivery-report polls in
 * Fineract's own wire shape.
 *
 * <p>Dispatch is deliberately NOT here — {@link FineractGatewayDispatchService}
 * runs it async so the accept endpoint can return 202 immediately, which is
 * the only response Fineract accepts (its {@code connectAndSendToIntermediateServer}
 * treats anything other than 202, even 200, as a connection failure).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FineractGatewayMessageService {

    static final String CHANNEL_SMS = "SMS";
    static final String CHANNEL_WHATSAPP = "WHATSAPP";

    private static final DateTimeFormatter REPORT_TS =
            DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneOffset.UTC);

    private final FineractGatewayMessageRepository repository;
    private final FineractGatewayProperties properties;

    /**
     * Persist a queue batch and return the row ids that need dispatching.
     * Per-item outcomes:
     * <ul>
     *   <li>missing {@code internalId} — dropped (unreportable, logged);</li>
     *   <li>blank mobile/message — stored as INVALID(0) so the report poll
     *       resolves it, never dispatched;</li>
     *   <li>duplicate of a PENDING/SENT row — skipped (dedupe);</li>
     *   <li>duplicate of a FAILED/INVALID row — re-queued with the fresh
     *       payload (Fineract only ever re-sends after a failure);</li>
     *   <li>otherwise — stored PENDING(100) for async dispatch.</li>
     * </ul>
     */
    @Transactional
    public List<Long> acceptBatch(String tenantId, List<FineractSmsQueueItemDTO> items) {
        List<Long> toDispatch = new ArrayList<>();
        for (FineractSmsQueueItemDTO item : items) {
            if (item == null || item.getInternalId() == null) {
                log.warn("Fineract gateway: dropping queue item without internalId (unreportable)");
                continue;
            }
            FineractGatewayMessage row = repository
                    .findByTenantIdAndFineractId(tenantId, item.getInternalId())
                    .orElse(null);
            if (row != null) {
                if (row.getDeliveryStatus() == FineractGatewayMessage.STATUS_PENDING
                        || row.getDeliveryStatus() == FineractGatewayMessage.STATUS_SENT) {
                    // Already queued or already delivered to the channel
                    // gateway — a batch retry must not double-send an OTP.
                    continue;
                }
                requeue(row, item);
                toDispatch.add(row.getId());
                continue;
            }
            row = newRow(tenantId, item);
            repository.save(row);
            if (row.getDeliveryStatus() == FineractGatewayMessage.STATUS_PENDING) {
                toDispatch.add(row.getId());
            }
        }
        return toDispatch;
    }

    /**
     * Build the delivery-report response for a poll. {@code hasError} is
     * ALWAYS false: Fineract's tasklet skips any row with
     * {@code hasError == true} (and any with status 100), so a failure must
     * ride {@code deliveryStatus=400, hasError=false} to actually flip the
     * Fineract row to FAILED. Unknown ids are reported as FAILED too — the
     * send batch never reached this gateway, and answering terminally stops
     * Fineract polling for them forever.
     */
    @Transactional(readOnly = true)
    public List<FineractSmsDeliveryReportDTO> buildReports(String tenantId, List<Long> fineractIds) {
        Map<Long, FineractGatewayMessage> byFineractId = new HashMap<>();
        for (FineractGatewayMessage row : repository.findByTenantIdAndFineractIdIn(tenantId, fineractIds)) {
            byFineractId.put(row.getFineractId(), row);
        }
        List<FineractSmsDeliveryReportDTO> reports = new ArrayList<>();
        for (Long fineractId : fineractIds) {
            if (fineractId == null) {
                continue;
            }
            FineractGatewayMessage row = byFineractId.get(fineractId);
            if (row == null) {
                reports.add(FineractSmsDeliveryReportDTO.builder()
                        .id(fineractId)
                        .deliveryStatus(FineractGatewayMessage.STATUS_FAILED)
                        .hasError(Boolean.FALSE)
                        .errorMessage("Message was never received by the notification gateway")
                        .build());
                continue;
            }
            reports.add(FineractSmsDeliveryReportDTO.builder()
                    .id(row.getFineractId())
                    .externalId(row.getExternalRef())
                    .addedOnDate(format(row.getCreatedAt()))
                    .deliveredOnDate(format(row.getSentAt()))
                    .deliveryStatus(row.getDeliveryStatus())
                    .hasError(Boolean.FALSE)
                    .errorMessage(row.getErrorMessage())
                    .build());
        }
        return reports;
    }

    private FineractGatewayMessage newRow(String tenantId, FineractSmsQueueItemDTO item) {
        String mobile = item.getMobileNumber() == null ? null : item.getMobileNumber().trim();
        FineractGatewayMessage.FineractGatewayMessageBuilder builder = FineractGatewayMessage.builder()
                .fineractId(item.getInternalId())
                .tenantId(tenantId)
                .mobileNumber(mobile == null || mobile.isBlank() ? "-" : mobile)
                .providerId(item.getProviderId())
                .channel(resolveChannel(item.getProviderId()))
                .externalRef("FIN-GW-" + UUID.randomUUID())
                .createdAt(Instant.now());
        if (mobile == null || mobile.isBlank()) {
            builder.deliveryStatus(FineractGatewayMessage.STATUS_INVALID)
                    .errorMessage("Mobile number is blank");
        } else if (item.getMessage() == null || item.getMessage().isBlank()) {
            builder.deliveryStatus(FineractGatewayMessage.STATUS_INVALID)
                    .errorMessage("Message body is blank");
        } else {
            builder.deliveryStatus(FineractGatewayMessage.STATUS_PENDING)
                    .message(item.getMessage());
        }
        return builder.build();
    }

    private void requeue(FineractGatewayMessage row, FineractSmsQueueItemDTO item) {
        String mobile = item.getMobileNumber() == null ? "" : item.getMobileNumber().trim();
        if (mobile.isBlank() || item.getMessage() == null || item.getMessage().isBlank()) {
            row.setDeliveryStatus(FineractGatewayMessage.STATUS_INVALID);
            row.setErrorMessage(mobile.isBlank() ? "Mobile number is blank" : "Message body is blank");
            row.setMessage(null);
            return;
        }
        row.setMobileNumber(mobile);
        row.setMessage(item.getMessage());
        row.setProviderId(item.getProviderId());
        row.setChannel(resolveChannel(item.getProviderId()));
        row.setDeliveryStatus(FineractGatewayMessage.STATUS_PENDING);
        row.setErrorMessage(null);
        row.setSentAt(null);
    }

    /** WhatsApp when the campaign's provider id is configured as such; SMS otherwise. */
    String resolveChannel(Long providerId) {
        if (providerId != null && properties.getWhatsappProviderIds() != null
                && properties.getWhatsappProviderIds().contains(providerId)) {
            return CHANNEL_WHATSAPP;
        }
        return CHANNEL_SMS;
    }

    private static String format(Instant instant) {
        return instant == null ? null : REPORT_TS.format(instant);
    }
}
