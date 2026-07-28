package com.innbucks.userservice.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One element of the JSON array Fineract POSTs to
 * {@code /fineract-gateway/sms} — the wire shape of Fineract's
 * {@code SmsMessageApiQueueResourceData}, serialized with Gson (nulls
 * omitted, so every field except {@code internalId}/{@code mobileNumber}/
 * {@code message}/{@code providerId} is routinely absent on triggered sends).
 * Field names are Fineract's contract — do not rename.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class FineractSmsQueueItemDTO {

    /** Fineract's internal m_sms_messages id — the report key. */
    private Long internalId;
    private String tenantId;
    private String createdOnDate;
    private String sourceAddress;
    private String mobileNumber;
    private String message;
    private Long providerId;
}
