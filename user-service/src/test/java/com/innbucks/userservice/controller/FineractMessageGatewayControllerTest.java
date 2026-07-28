package com.innbucks.userservice.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.innbucks.userservice.config.FineractGatewayProperties;
import com.innbucks.userservice.dto.ApiResult;
import com.innbucks.userservice.dto.FineractSmsDeliveryReportDTO;
import com.innbucks.userservice.dto.FineractSmsQueueItemDTO;
import com.innbucks.userservice.service.AuditService;
import com.innbucks.userservice.service.FineractGatewayDispatchService;
import com.innbucks.userservice.service.FineractGatewayMessageService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Security + contract tests for the Fineract Message Gateway facade.
 *
 * <p>Mirrors {@link InternalUserLookupControllerTest}: auth rejections assert
 * the SPECIFIC 401 (never {@code is4xxClientError()}, per CLAUDE.md) and the
 * store/dispatch collaborators are only touched once the app key matches.
 * Also pins the two Fineract-contract quirks: the queue endpoint answers
 * <b>202</b> (Fineract treats anything else — even 200 — as a connection
 * failure), and the report endpoint returns the RAW report list, not the
 * ApiResult envelope.
 */
class FineractMessageGatewayControllerTest {

    private static final String KEY = "the-fineract-app-key";
    private static final String TENANT = "default";

    private final FineractGatewayMessageService messageService = mock(FineractGatewayMessageService.class);
    private final FineractGatewayDispatchService dispatchService = mock(FineractGatewayDispatchService.class);

    private FineractMessageGatewayController controller(String expectedKey) {
        FineractGatewayProperties props = new FineractGatewayProperties();
        props.setTenantAppKey(expectedKey);
        props.setTenantId(TENANT);
        FineractGatewayAuthorizer authorizer =
                new FineractGatewayAuthorizer(props, mock(AuditService.class));
        return new FineractMessageGatewayController(authorizer, messageService, dispatchService);
    }

    private static HttpServletRequest request() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRequestURI()).thenReturn("/fineract-gateway/sms");
        when(req.getRemoteAddr()).thenReturn("10.42.0.7");
        return req;
    }

    private static FineractSmsQueueItemDTO item(long internalId) {
        return FineractSmsQueueItemDTO.builder()
                .internalId(internalId)
                .mobileNumber("+263771234567")
                .message("Your OTP is 123456")
                .providerId(1L)
                .build();
    }

    // ---- auth ------------------------------------------------------------

    @Test
    void missingAppKey_isRejectedWith401_andNothingStored() {
        ResponseEntity<ApiResult<Void>> resp = controller(KEY)
                .queueSms(null, TENANT, List.of(item(1)), request());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(messageService, dispatchService);
    }

    @Test
    void wrongAppKey_isRejectedWith401() {
        ResponseEntity<ApiResult<Void>> resp = controller(KEY)
                .queueSms("not-the-key", TENANT, List.of(item(1)), request());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(messageService, dispatchService);
    }

    @Test
    void foreignTenant_isRejectedWith401_evenWithValidKey() {
        ResponseEntity<ApiResult<Void>> resp = controller(KEY)
                .queueSms(KEY, "other-cell", List.of(item(1)), request());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(messageService, dispatchService);
    }

    @Test
    void reportEndpoint_missingAppKey_isRejectedWith401() {
        ResponseEntity<List<FineractSmsDeliveryReportDTO>> resp = controller(KEY)
                .deliveryReports(null, TENANT, List.of(1L), request());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(messageService);
    }

    // ---- queue contract --------------------------------------------------

    @Test
    void acceptedBatch_returns202_andDispatchesStoredRows() {
        when(messageService.acceptBatch(eq(TENANT), anyList())).thenReturn(List.of(11L, 12L));

        ResponseEntity<ApiResult<Void>> resp = controller(KEY)
                .queueSms(KEY, TENANT, List.of(item(1), item(2)), request());

        // 202 specifically: Fineract's connectAndSendToIntermediateServer
        // throws ConnectionFailureException on ANY other status, including 200.
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        verify(dispatchService).dispatchAsync(List.of(11L, 12L));
    }

    @Test
    void batchWithNothingDispatchable_stillReturns202_withoutDispatching() {
        when(messageService.acceptBatch(eq(TENANT), anyList())).thenReturn(List.of());

        ResponseEntity<ApiResult<Void>> resp = controller(KEY)
                .queueSms(KEY, TENANT, List.of(item(1)), request());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        verify(dispatchService, never()).dispatchAsync(any());
    }

    /**
     * The exact JSON Fineract's Gson emits for a triggered send (nulls
     * omitted — only internalId/mobileNumber/message/providerId present) must
     * bind onto our DTO without loss or failure.
     */
    @Test
    void fineractGsonWireShape_bindsOntoQueueItemDto() throws Exception {
        String fineractPayload = """
                [{"internalId":42,"mobileNumber":"+263771234567",\
                "message":"Hello from Fineract","providerId":2}]""";

        FineractSmsQueueItemDTO[] items =
                new ObjectMapper().readValue(fineractPayload, FineractSmsQueueItemDTO[].class);

        assertThat(items).hasSize(1);
        assertThat(items[0].getInternalId()).isEqualTo(42L);
        assertThat(items[0].getMobileNumber()).isEqualTo("+263771234567");
        assertThat(items[0].getMessage()).isEqualTo("Hello from Fineract");
        assertThat(items[0].getProviderId()).isEqualTo(2L);
        assertThat(items[0].getTenantId()).isNull();
    }

    // ---- report contract -------------------------------------------------

    @Test
    void reportEndpoint_returnsRawReportList_notApiResultEnvelope() {
        FineractSmsDeliveryReportDTO report = FineractSmsDeliveryReportDTO.builder()
                .id(42L).externalId("FIN-GW-abc").deliveryStatus(200).hasError(Boolean.FALSE)
                .build();
        when(messageService.buildReports(TENANT, List.of(42L))).thenReturn(List.of(report));

        ResponseEntity<List<FineractSmsDeliveryReportDTO>> resp = controller(KEY)
                .deliveryReports(KEY, TENANT, List.of(42L), request());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Raw list body — Fineract deserializes Collection<SmsMessageDeliveryReportData>
        // directly; an ApiResult wrapper would break its tasklet.
        assertThat(resp.getBody()).containsExactly(report);
    }
}
