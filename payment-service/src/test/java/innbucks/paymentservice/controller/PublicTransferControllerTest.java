package innbucks.paymentservice.controller;

import innbucks.paymentservice.client.OradianMiddlewareClient;
import innbucks.paymentservice.dto.ApiResult;
import innbucks.paymentservice.dto.DepositAccount;
import innbucks.paymentservice.dto.DepositTransferRequest;
import innbucks.paymentservice.dto.DepositTransferResponse;
import innbucks.paymentservice.security.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PublicTransferControllerTest {

    private static final String VALID_TOKEN = "valid.jwt.token";
    private static final String CUSTOMER_PHONE = "+263771234567";
    private static final String OWNED_ACCOUNT = "A000001";
    private static final String DEST_ACCOUNT = "A000002";

    private static DepositTransferRequest request(String fromAccount) {
        // transactionDate is server-stamped by the controller — clients do not
        // supply it, so we deliberately leave it null on the inbound request.
        return DepositTransferRequest.builder()
                .fromAccountId(fromAccount)
                .toAccountId(DEST_ACCOUNT)
                .amount("123.00")
                .notes("Lunch")
                .build();
    }

    private static DepositAccount ownedAccount(String id) {
        return DepositAccount.builder().ID(id).status("Active").build();
    }

    private static HttpServletRequest bearerRequest(String token) {
        HttpServletRequest http = mock(HttpServletRequest.class);
        when(http.getHeader("Authorization")).thenReturn(token == null ? null : "Bearer " + token);
        return http;
    }

    @Test
    void transferDeposit_happyPath_forwardsToOradianAndReturns200() {
        JwtUtil jwt = mock(JwtUtil.class);
        when(jwt.isTokenValid(VALID_TOKEN)).thenReturn(true);
        when(jwt.extractPhoneNumber(VALID_TOKEN)).thenReturn(CUSTOMER_PHONE);

        OradianMiddlewareClient oradian = mock(OradianMiddlewareClient.class);
        when(oradian.getDepositsForMsisdn(CUSTOMER_PHONE))
                .thenReturn(List.of(ownedAccount(OWNED_ACCOUNT), ownedAccount("A000099")));

        DepositTransferResponse upstream = DepositTransferResponse.builder()
                .fromAccountId(OWNED_ACCOUNT)
                .toAccountId(DEST_ACCOUNT)
                .amount("123.00")
                .transactionID("1155")
                .referenceNumber("1234567980123")
                .transactionDate(LocalDate.now())
                .build();
        when(oradian.submitDepositTransfer(any(DepositTransferRequest.class))).thenReturn(upstream);

        ResponseEntity<ApiResult<DepositTransferResponse>> resp =
                new PublicTransferController(jwt, oradian)
                        .transferDeposit(bearerRequest(VALID_TOKEN), request(OWNED_ACCOUNT));

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals("1155", resp.getBody().getData().getTransactionID());
        verify(oradian).submitDepositTransfer(any(DepositTransferRequest.class));
    }

    @Test
    void transferDeposit_coercesNullNotesToEmptyStringBeforeForwardingToOradian() {
        // Oradian Instafin's SubmitDepositAccountTransfer marks `notes` as a
        // required field. With @JsonInclude(NON_NULL) on the DTO, a null
        // notes value would be omitted from the JSON sent upstream and
        // Oradian would 422 the whole request. The controller MUST coerce
        // null -> "" so the field is always present on the wire.
        JwtUtil jwt = mock(JwtUtil.class);
        when(jwt.isTokenValid(VALID_TOKEN)).thenReturn(true);
        when(jwt.extractPhoneNumber(VALID_TOKEN)).thenReturn(CUSTOMER_PHONE);

        OradianMiddlewareClient oradian = mock(OradianMiddlewareClient.class);
        when(oradian.getDepositsForMsisdn(CUSTOMER_PHONE))
                .thenReturn(List.of(ownedAccount(OWNED_ACCOUNT)));
        when(oradian.submitDepositTransfer(any(DepositTransferRequest.class)))
                .thenReturn(DepositTransferResponse.builder().transactionID("ok").build());

        DepositTransferRequest body = DepositTransferRequest.builder()
                .fromAccountId(OWNED_ACCOUNT)
                .toAccountId(DEST_ACCOUNT)
                .amount("123.00")
                .build(); // notes intentionally null

        new PublicTransferController(jwt, oradian)
                .transferDeposit(bearerRequest(VALID_TOKEN), body);

        ArgumentCaptor<DepositTransferRequest> forwarded = ArgumentCaptor.forClass(DepositTransferRequest.class);
        verify(oradian).submitDepositTransfer(forwarded.capture());
        assertEquals("", forwarded.getValue().getNotes(),
                "notes must be coerced from null to \"\" before forwarding");
    }

    @Test
    void transferDeposit_keepsCallerSuppliedNotesIntactWhenPresent() {
        JwtUtil jwt = mock(JwtUtil.class);
        when(jwt.isTokenValid(VALID_TOKEN)).thenReturn(true);
        when(jwt.extractPhoneNumber(VALID_TOKEN)).thenReturn(CUSTOMER_PHONE);

        OradianMiddlewareClient oradian = mock(OradianMiddlewareClient.class);
        when(oradian.getDepositsForMsisdn(CUSTOMER_PHONE))
                .thenReturn(List.of(ownedAccount(OWNED_ACCOUNT)));
        when(oradian.submitDepositTransfer(any(DepositTransferRequest.class)))
                .thenReturn(DepositTransferResponse.builder().transactionID("ok").build());

        DepositTransferRequest body = DepositTransferRequest.builder()
                .fromAccountId(OWNED_ACCOUNT)
                .toAccountId(DEST_ACCOUNT)
                .amount("123.00")
                .notes("School fees")
                .build();

        new PublicTransferController(jwt, oradian)
                .transferDeposit(bearerRequest(VALID_TOKEN), body);

        ArgumentCaptor<DepositTransferRequest> forwarded = ArgumentCaptor.forClass(DepositTransferRequest.class);
        verify(oradian).submitDepositTransfer(forwarded.capture());
        assertEquals("School fees", forwarded.getValue().getNotes());
    }

    @Test
    void transferDeposit_stampsTodayAsTransactionDateBeforeForwardingToOradian() {
        // The DTO marks transactionDate as JsonIgnore on input, but defence-
        // in-depth: even if a malicious client bypassed Jackson and got a date
        // onto the object somehow, the controller MUST overwrite it. This
        // test pins the contract that the value sent downstream is the
        // server's LocalDate.now(), not whatever was on the inbound request.
        JwtUtil jwt = mock(JwtUtil.class);
        when(jwt.isTokenValid(VALID_TOKEN)).thenReturn(true);
        when(jwt.extractPhoneNumber(VALID_TOKEN)).thenReturn(CUSTOMER_PHONE);

        OradianMiddlewareClient oradian = mock(OradianMiddlewareClient.class);
        when(oradian.getDepositsForMsisdn(CUSTOMER_PHONE))
                .thenReturn(List.of(ownedAccount(OWNED_ACCOUNT)));
        when(oradian.submitDepositTransfer(any(DepositTransferRequest.class)))
                .thenReturn(DepositTransferResponse.builder().transactionID("ok").build());

        DepositTransferRequest body = request(OWNED_ACCOUNT);
        body.setTransactionDate(LocalDate.of(1999, 1, 1)); // attacker-supplied; must be ignored

        LocalDate before = LocalDate.now();
        new PublicTransferController(jwt, oradian)
                .transferDeposit(bearerRequest(VALID_TOKEN), body);
        LocalDate after = LocalDate.now();

        ArgumentCaptor<DepositTransferRequest> forwarded = ArgumentCaptor.forClass(DepositTransferRequest.class);
        verify(oradian).submitDepositTransfer(forwarded.capture());
        LocalDate stamped = forwarded.getValue().getTransactionDate();
        assertNotNull(stamped, "transactionDate must be stamped by the controller");
        assertNotEquals(LocalDate.of(1999, 1, 1), stamped, "attacker-supplied date must be overwritten");
        // LocalDate.now() may roll over mid-test on the stroke of midnight,
        // so accept either the date observed just before or just after.
        assertTrue(stamped.equals(before) || stamped.equals(after),
                "stamped date " + stamped + " should be today (between " + before + " and " + after + ")");
    }

    @Test
    void transferDeposit_returns401_whenAuthorizationHeaderIsMissing() {
        JwtUtil jwt = mock(JwtUtil.class);
        OradianMiddlewareClient oradian = mock(OradianMiddlewareClient.class);

        HttpServletRequest http = mock(HttpServletRequest.class);
        when(http.getHeader("Authorization")).thenReturn(null);

        ResponseEntity<ApiResult<DepositTransferResponse>> resp =
                new PublicTransferController(jwt, oradian)
                        .transferDeposit(http, request(OWNED_ACCOUNT));

        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
        assertEquals("Missing Bearer token", resp.getBody().getMessage());
        verifyNoInteractions(jwt);
        verifyNoInteractions(oradian);
    }

    @Test
    void transferDeposit_returns401_whenAuthorizationHeaderHasNoBearerPrefix() {
        JwtUtil jwt = mock(JwtUtil.class);
        OradianMiddlewareClient oradian = mock(OradianMiddlewareClient.class);

        HttpServletRequest http = mock(HttpServletRequest.class);
        when(http.getHeader("Authorization")).thenReturn("Basic abc123");

        ResponseEntity<ApiResult<DepositTransferResponse>> resp =
                new PublicTransferController(jwt, oradian)
                        .transferDeposit(http, request(OWNED_ACCOUNT));

        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
        verifyNoInteractions(oradian);
    }

    @Test
    void transferDeposit_returns401_whenTokenSignatureIsInvalid() {
        JwtUtil jwt = mock(JwtUtil.class);
        when(jwt.isTokenValid("bad-token")).thenReturn(false);

        OradianMiddlewareClient oradian = mock(OradianMiddlewareClient.class);

        ResponseEntity<ApiResult<DepositTransferResponse>> resp =
                new PublicTransferController(jwt, oradian)
                        .transferDeposit(bearerRequest("bad-token"), request(OWNED_ACCOUNT));

        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
        assertEquals("Invalid or expired bearer token", resp.getBody().getMessage());
        verifyNoInteractions(oradian);
    }

    @Test
    void transferDeposit_returns401_whenTokenHasNoPhoneNumberClaim() {
        JwtUtil jwt = mock(JwtUtil.class);
        when(jwt.isTokenValid(VALID_TOKEN)).thenReturn(true);
        when(jwt.extractPhoneNumber(VALID_TOKEN)).thenReturn(null);

        OradianMiddlewareClient oradian = mock(OradianMiddlewareClient.class);

        ResponseEntity<ApiResult<DepositTransferResponse>> resp =
                new PublicTransferController(jwt, oradian)
                        .transferDeposit(bearerRequest(VALID_TOKEN), request(OWNED_ACCOUNT));

        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
        assertTrue(resp.getBody().getMessage().contains("phoneNumber claim"));
        verifyNoInteractions(oradian);
    }

    @Test
    void transferDeposit_returns403_whenFromAccountIdIsNotOwnedByCaller() {
        JwtUtil jwt = mock(JwtUtil.class);
        when(jwt.isTokenValid(VALID_TOKEN)).thenReturn(true);
        when(jwt.extractPhoneNumber(VALID_TOKEN)).thenReturn(CUSTOMER_PHONE);

        OradianMiddlewareClient oradian = mock(OradianMiddlewareClient.class);
        when(oradian.getDepositsForMsisdn(CUSTOMER_PHONE))
                .thenReturn(List.of(ownedAccount("A000099"), ownedAccount("A000100")));

        ResponseEntity<ApiResult<DepositTransferResponse>> resp =
                new PublicTransferController(jwt, oradian)
                        .transferDeposit(bearerRequest(VALID_TOKEN), request(OWNED_ACCOUNT));

        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
        assertEquals("fromAccountId does not belong to the authenticated customer",
                resp.getBody().getMessage());
        verify(oradian).getDepositsForMsisdn(eq(CUSTOMER_PHONE));
        verify(oradian, never()).submitDepositTransfer(any());
    }

    @Test
    void transferDeposit_returns403_whenCallerHasNoOradianDepositsAtAll() {
        JwtUtil jwt = mock(JwtUtil.class);
        when(jwt.isTokenValid(VALID_TOKEN)).thenReturn(true);
        when(jwt.extractPhoneNumber(VALID_TOKEN)).thenReturn(CUSTOMER_PHONE);

        OradianMiddlewareClient oradian = mock(OradianMiddlewareClient.class);
        when(oradian.getDepositsForMsisdn(CUSTOMER_PHONE)).thenReturn(List.of());

        ResponseEntity<ApiResult<DepositTransferResponse>> resp =
                new PublicTransferController(jwt, oradian)
                        .transferDeposit(bearerRequest(VALID_TOKEN), request(OWNED_ACCOUNT));

        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
        verify(oradian, never()).submitDepositTransfer(any());
    }

    @Test
    void transferDeposit_ignoresDepositsWithBlankIdsWhenMatchingOwnership() {
        // Defensive: Oradian sometimes returns rows with an empty `ID` string
        // (placeholder accounts). A caller sending fromAccountId="" must not
        // be allowed to match those — that would let anyone with any valid
        // JWT bypass the ownership check by sending an empty fromAccountId.
        JwtUtil jwt = mock(JwtUtil.class);
        when(jwt.isTokenValid(VALID_TOKEN)).thenReturn(true);
        when(jwt.extractPhoneNumber(VALID_TOKEN)).thenReturn(CUSTOMER_PHONE);

        OradianMiddlewareClient oradian = mock(OradianMiddlewareClient.class);
        when(oradian.getDepositsForMsisdn(CUSTOMER_PHONE))
                .thenReturn(List.of(ownedAccount(""), ownedAccount(null)));

        ResponseEntity<ApiResult<DepositTransferResponse>> resp =
                new PublicTransferController(jwt, oradian)
                        .transferDeposit(bearerRequest(VALID_TOKEN), request(""));

        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
        verify(oradian, never()).submitDepositTransfer(any());
    }
}
