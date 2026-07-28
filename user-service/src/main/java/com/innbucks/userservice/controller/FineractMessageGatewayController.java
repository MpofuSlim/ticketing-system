package com.innbucks.userservice.controller;

import com.innbucks.userservice.dto.ApiResult;
import com.innbucks.userservice.dto.FineractSmsDeliveryReportDTO;
import com.innbucks.userservice.dto.FineractSmsQueueItemDTO;
import com.innbucks.userservice.service.FineractGatewayDispatchService;
import com.innbucks.userservice.service.FineractGatewayMessageService;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Inbound facade implementing the Mifos/Fineract <b>Message Gateway</b> wire
 * contract, so OUR Fineract deployment can deliver SMS/WhatsApp through the
 * platform's existing notification channels. Fineract side: configure the
 * {@code MESSAGE_GATEWAY} external service with {@code host_name}/{@code
 * port_number} of user-service, {@code end_point=fineract-gateway} and
 * {@code tenant_app_key} = our {@code FINERACT_GATEWAY_APP_KEY}.
 *
 * <p>Contract quirks this controller honors (pinned by Fineract's
 * {@code SmsMessageScheduledJobServiceImpl} / {@code
 * GetDeliveryReportsFromSmsGatewayTasklet}):
 * <ul>
 *   <li>{@code POST /sms} MUST return <b>202</b> — Fineract treats any other
 *       status, including 200, as a gateway connection failure.</li>
 *   <li>{@code POST /sms/report} returns a RAW JSON array of report objects
 *       (no ApiResult envelope — Fineract deserializes the body directly),
 *       which is a deliberate, called-out deviation from this repo's
 *       envelope convention.</li>
 *   <li>Stock Fineract builds the report path via a JAX-RS UriBuilder
 *       template that percent-encodes the slash in {@code sms/report}; our
 *       Fineract fork carries the path-building fix, and this controller
 *       exposes the clean {@code /sms/report} path.</li>
 * </ul>
 *
 * <p>Auth is the {@code Fineract-Tenant-App-Key} header (constant-time
 * compare in {@link FineractGatewayAuthorizer}) + a pinned
 * {@code Fineract-Platform-TenantId}. Same three-files posture as
 * {@code /users/internal/**}: SecurityConfig permitAlls the path so the
 * controller's own check runs, and the api-gateway blocks
 * {@code /fineract-gateway/**} at the edge ({@code fineract-gateway-deny}),
 * so this is S2S-only and unreachable from the public internet.
 */
@RestController
@RequestMapping("/fineract-gateway")
@Slf4j
@Hidden
public class FineractMessageGatewayController {

    private final FineractGatewayAuthorizer authorizer;
    private final FineractGatewayMessageService messageService;
    private final FineractGatewayDispatchService dispatchService;

    public FineractMessageGatewayController(FineractGatewayAuthorizer authorizer,
                                            FineractGatewayMessageService messageService,
                                            FineractGatewayDispatchService dispatchService) {
        this.authorizer = authorizer;
        this.messageService = messageService;
        this.dispatchService = dispatchService;
    }

    @PostMapping("/sms")
    @Operation(summary = "(S2S, Fineract) Accept an outbound SMS/WhatsApp batch",
            description = "Fineract Message Gateway queue endpoint. Body is Fineract's "
                    + "SmsMessageApiQueueResourceData array; messages are stored, deduped by "
                    + "(tenant, internalId) and dispatched async to the SMS or WhatsApp channel "
                    + "by provider id. Always 202 on acceptance (Fineract's contract); 401 on a "
                    + "missing/invalid Fineract-Tenant-App-Key or foreign tenant id.")
    public ResponseEntity<ApiResult<Void>> queueSms(
            @RequestHeader(value = FineractGatewayAuthorizer.APP_KEY_HEADER, required = false) String appKey,
            @RequestHeader(value = FineractGatewayAuthorizer.TENANT_HEADER, required = false) String tenantId,
            @RequestBody List<FineractSmsQueueItemDTO> items,
            HttpServletRequest request) {
        if (!authorizer.authorized(appKey, tenantId, request)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        List<Long> toDispatch = messageService.acceptBatch(tenantId, items);
        if (!toDispatch.isEmpty()) {
            dispatchService.dispatchAsync(toDispatch);
        }
        log.info("Fineract gateway accepted batch size={} dispatchable={} tenant={}",
                items.size(), toDispatch.size(), tenantId);
        return ResponseEntity.accepted()
                .body(ApiResult.of(HttpStatus.ACCEPTED, "Batch accepted", null));
    }

    @PostMapping("/sms/report")
    @Operation(summary = "(S2S, Fineract) Delivery-report poll",
            description = "Fineract Message Gateway report endpoint. Body is a JSON array of "
                    + "Fineract message ids; response is a RAW array of SmsMessageDeliveryReportData "
                    + "objects (no ApiResult envelope — Fineract deserializes the body directly). "
                    + "Unknown ids are answered as FAILED so Fineract stops polling them. 401 on a "
                    + "missing/invalid Fineract-Tenant-App-Key or foreign tenant id.")
    public ResponseEntity<List<FineractSmsDeliveryReportDTO>> deliveryReports(
            @RequestHeader(value = FineractGatewayAuthorizer.APP_KEY_HEADER, required = false) String appKey,
            @RequestHeader(value = FineractGatewayAuthorizer.TENANT_HEADER, required = false) String tenantId,
            @RequestBody List<Long> fineractIds,
            HttpServletRequest request) {
        if (!authorizer.authorized(appKey, tenantId, request)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(messageService.buildReports(tenantId, fineractIds));
    }
}
