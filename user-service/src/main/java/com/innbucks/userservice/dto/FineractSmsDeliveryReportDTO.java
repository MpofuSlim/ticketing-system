package com.innbucks.userservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One element of the JSON array returned by
 * {@code POST /fineract-gateway/sms/report} — the wire shape of Fineract's
 * {@code SmsMessageDeliveryReportData}. Fineract's delivery-report tasklet
 * deserializes the response body DIRECTLY as a collection of these (no
 * envelope), so this endpoint must NOT wrap in the ApiResult envelope.
 *
 * <p>Contract notes pinned by the consumer
 * ({@code GetDeliveryReportsFromSmsGatewayTasklet}):
 * <ul>
 *   <li>{@code id} is FINERACT's message id (our {@code fineract_id}), not a
 *       gateway row id.</li>
 *   <li>{@code hasError} is unboxed by Fineract — it must never be null.</li>
 *   <li>{@code deliveryStatus} uses Fineract's SmsMessageStatusType codes
 *       (0/100/150/200/300/400); Fineract ignores rows with
 *       {@code deliveryStatus == 100} or {@code hasError == true}... the
 *       latter only for the status update, so FAILED must ride
 *       {@code deliveryStatus=400, hasError=false} to actually mark the
 *       Fineract row FAILED.</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FineractSmsDeliveryReportDTO {

    private Long id;
    private String externalId;
    private String addedOnDate;
    private String deliveredOnDate;
    private Integer deliveryStatus;
    private Boolean hasError;
    private String errorMessage;
}
