package innbucks.paymentservice.controller;

import innbucks.paymentservice.client.OradianMiddlewareClient;
import innbucks.paymentservice.dto.ApiResult;
import innbucks.paymentservice.dto.DepositAccount;
import innbucks.paymentservice.dto.DepositTransferRequest;
import innbucks.paymentservice.dto.DepositTransferResponse;
import innbucks.paymentservice.security.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
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
        return DepositTransferRequest.builder()
                .fromAccountId(fromAccount)
                .toAccountId(DEST_ACCOUNT)
                .amount("123.00")
                .notes("Lunch")
                .transactionDate(LocalDate.of(2026, 5, 18))
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
                .transactionDate(LocalDate.of(2026, 5, 18))
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
