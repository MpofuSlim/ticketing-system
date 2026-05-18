package innbucks.paymentservice.controller;

import innbucks.paymentservice.client.OradianMiddlewareClient;
import innbucks.paymentservice.client.OradianMiddlewareException;
import innbucks.paymentservice.dto.ApiResult;
import innbucks.paymentservice.dto.DepositAccount;
import innbucks.paymentservice.dto.DepositTransferRequest;
import innbucks.paymentservice.dto.DepositTransferResponse;
import innbucks.paymentservice.dto.WithdrawalRequest;
import innbucks.paymentservice.dto.WithdrawalResponse;
import innbucks.paymentservice.entity.Transaction;
import innbucks.paymentservice.entity.TransactionType;
import innbucks.paymentservice.security.JwtUtil;
import innbucks.paymentservice.service.TransactionService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class TransfersControllerTest {

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

    /**
     * Stubbed {@link TransactionService} for tests that don't care to assert
     * on ledger writes — openPending returns an entity with a generated id
     * so the controller doesn't NPE when reading {@code ledger.getId()}.
     * Tests that DO want to verify ledger interactions construct their own
     * mock locally so they can capture args.
     */
    private static TransactionService stubbedTxService() {
        TransactionService svc = mock(TransactionService.class);
        when(svc.openPending(any(Transaction.class))).thenAnswer(inv -> {
            Transaction t = inv.getArgument(0);
            if (t.getId() == null) t.setId(UUID.randomUUID());
            return t;
        });
        return svc;
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
                new TransfersController(jwt, oradian, stubbedTxService())
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

        new TransfersController(jwt, oradian, stubbedTxService())
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

        new TransfersController(jwt, oradian, stubbedTxService())
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
        new TransfersController(jwt, oradian, stubbedTxService())
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
                new TransfersController(jwt, oradian, stubbedTxService())
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
                new TransfersController(jwt, oradian, stubbedTxService())
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
                new TransfersController(jwt, oradian, stubbedTxService())
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
                new TransfersController(jwt, oradian, stubbedTxService())
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
                new TransfersController(jwt, oradian, stubbedTxService())
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
                new TransfersController(jwt, oradian, stubbedTxService())
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
                new TransfersController(jwt, oradian, stubbedTxService())
                        .transferDeposit(bearerRequest(VALID_TOKEN), request(""));

        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
        verify(oradian, never()).submitDepositTransfer(any());
    }

    // ----- /payments/withdraw -----

    private static WithdrawalRequest withdrawalRequest(String accountId) {
        // transactionDate / transactionBranchID / overrideLimitCheck are
        // server-stamped — clients don't supply them, so leave null on input.
        return WithdrawalRequest.builder()
                .accountID(accountId)
                .paymentMethodName("Cash")
                .amount("10.00")
                .notes("Cash out")
                .build();
    }

    @Test
    void withdraw_happyPath_serverStampsFieldsAndReturns200() {
        JwtUtil jwt = mock(JwtUtil.class);
        when(jwt.isTokenValid(VALID_TOKEN)).thenReturn(true);
        when(jwt.extractPhoneNumber(VALID_TOKEN)).thenReturn(CUSTOMER_PHONE);

        OradianMiddlewareClient oradian = mock(OradianMiddlewareClient.class);
        when(oradian.getDepositsForMsisdn(CUSTOMER_PHONE))
                .thenReturn(List.of(ownedAccount(OWNED_ACCOUNT)));

        WithdrawalResponse upstream = WithdrawalResponse.builder()
                .accountID(OWNED_ACCOUNT)
                .amount("10.00")
                .paymentMethodName("Cash")
                .transactionBranchID("MobileBanking")
                .transactionDate(LocalDate.now())
                .transactionID("1151")
                .commandID("210")
                .referenceNumber("1234567890123")
                .build();
        when(oradian.submitWithdrawal(any(WithdrawalRequest.class))).thenReturn(upstream);

        ResponseEntity<ApiResult<WithdrawalResponse>> resp =
                new TransfersController(jwt, oradian, stubbedTxService())
                        .withdraw(bearerRequest(VALID_TOKEN), withdrawalRequest(OWNED_ACCOUNT));

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("1151", resp.getBody().getData().getTransactionID());

        ArgumentCaptor<WithdrawalRequest> forwarded = ArgumentCaptor.forClass(WithdrawalRequest.class);
        verify(oradian).submitWithdrawal(forwarded.capture());
        WithdrawalRequest sent = forwarded.getValue();
        assertEquals("MobileBanking", sent.getTransactionBranchID(),
                "transactionBranchID must be hardcoded to MobileBanking");
        assertEquals(Boolean.FALSE, sent.getOverrideLimitCheck(),
                "overrideLimitCheck must be false for customer-initiated withdrawals");
        assertNotNull(sent.getTransactionDate(), "transactionDate must be stamped by the controller");
    }

    @Test
    void withdraw_returns401_whenAuthorizationHeaderIsMissing() {
        JwtUtil jwt = mock(JwtUtil.class);
        OradianMiddlewareClient oradian = mock(OradianMiddlewareClient.class);

        HttpServletRequest http = mock(HttpServletRequest.class);
        when(http.getHeader("Authorization")).thenReturn(null);

        ResponseEntity<ApiResult<WithdrawalResponse>> resp =
                new TransfersController(jwt, oradian, stubbedTxService())
                        .withdraw(http, withdrawalRequest(OWNED_ACCOUNT));

        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
        assertEquals("Missing Bearer token", resp.getBody().getMessage());
        verifyNoInteractions(oradian);
    }

    @Test
    void withdraw_returns401_whenTokenSignatureIsInvalid() {
        JwtUtil jwt = mock(JwtUtil.class);
        when(jwt.isTokenValid("bad-token")).thenReturn(false);
        OradianMiddlewareClient oradian = mock(OradianMiddlewareClient.class);

        ResponseEntity<ApiResult<WithdrawalResponse>> resp =
                new TransfersController(jwt, oradian, stubbedTxService())
                        .withdraw(bearerRequest("bad-token"), withdrawalRequest(OWNED_ACCOUNT));

        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
        assertEquals("Invalid or expired bearer token", resp.getBody().getMessage());
        verifyNoInteractions(oradian);
    }

    @Test
    void withdraw_returns401_whenTokenHasNoPhoneNumberClaim() {
        JwtUtil jwt = mock(JwtUtil.class);
        when(jwt.isTokenValid(VALID_TOKEN)).thenReturn(true);
        when(jwt.extractPhoneNumber(VALID_TOKEN)).thenReturn(null);
        OradianMiddlewareClient oradian = mock(OradianMiddlewareClient.class);

        ResponseEntity<ApiResult<WithdrawalResponse>> resp =
                new TransfersController(jwt, oradian, stubbedTxService())
                        .withdraw(bearerRequest(VALID_TOKEN), withdrawalRequest(OWNED_ACCOUNT));

        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
        assertTrue(resp.getBody().getMessage().contains("phoneNumber claim"));
        verifyNoInteractions(oradian);
    }

    @Test
    void withdraw_returns403_whenAccountIdIsNotOwnedByCaller() {
        JwtUtil jwt = mock(JwtUtil.class);
        when(jwt.isTokenValid(VALID_TOKEN)).thenReturn(true);
        when(jwt.extractPhoneNumber(VALID_TOKEN)).thenReturn(CUSTOMER_PHONE);

        OradianMiddlewareClient oradian = mock(OradianMiddlewareClient.class);
        when(oradian.getDepositsForMsisdn(CUSTOMER_PHONE))
                .thenReturn(List.of(ownedAccount("A000099"), ownedAccount("A000100")));

        ResponseEntity<ApiResult<WithdrawalResponse>> resp =
                new TransfersController(jwt, oradian, stubbedTxService())
                        .withdraw(bearerRequest(VALID_TOKEN), withdrawalRequest(OWNED_ACCOUNT));

        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
        assertEquals("accountID does not belong to the authenticated customer",
                resp.getBody().getMessage());
        verify(oradian).getDepositsForMsisdn(eq(CUSTOMER_PHONE));
        verify(oradian, never()).submitWithdrawal(any());
    }

    @Test
    void withdraw_ignoresAttackerSuppliedReadOnlyFields() {
        // Defence-in-depth: even if a malicious client bypassed Jackson's
        // READ_ONLY annotation and got values onto the inbound DTO, the
        // controller MUST overwrite all three server-stamped fields.
        JwtUtil jwt = mock(JwtUtil.class);
        when(jwt.isTokenValid(VALID_TOKEN)).thenReturn(true);
        when(jwt.extractPhoneNumber(VALID_TOKEN)).thenReturn(CUSTOMER_PHONE);

        OradianMiddlewareClient oradian = mock(OradianMiddlewareClient.class);
        when(oradian.getDepositsForMsisdn(CUSTOMER_PHONE))
                .thenReturn(List.of(ownedAccount(OWNED_ACCOUNT)));
        when(oradian.submitWithdrawal(any(WithdrawalRequest.class)))
                .thenReturn(WithdrawalResponse.builder().transactionID("ok").build());

        WithdrawalRequest body = withdrawalRequest(OWNED_ACCOUNT);
        body.setTransactionDate(LocalDate.of(1999, 1, 1));
        body.setTransactionBranchID("HeadOffice");
        body.setOverrideLimitCheck(true);

        new TransfersController(jwt, oradian, stubbedTxService())
                .withdraw(bearerRequest(VALID_TOKEN), body);

        ArgumentCaptor<WithdrawalRequest> forwarded = ArgumentCaptor.forClass(WithdrawalRequest.class);
        verify(oradian).submitWithdrawal(forwarded.capture());
        WithdrawalRequest sent = forwarded.getValue();
        assertNotEquals(LocalDate.of(1999, 1, 1), sent.getTransactionDate(),
                "attacker-supplied transactionDate must be overwritten");
        assertEquals("MobileBanking", sent.getTransactionBranchID(),
                "attacker-supplied transactionBranchID must be overwritten");
        assertEquals(Boolean.FALSE, sent.getOverrideLimitCheck(),
                "attacker-supplied overrideLimitCheck must be forced to false");
    }

    @Test
    void withdraw_coercesNullNotesToEmptyString() {
        JwtUtil jwt = mock(JwtUtil.class);
        when(jwt.isTokenValid(VALID_TOKEN)).thenReturn(true);
        when(jwt.extractPhoneNumber(VALID_TOKEN)).thenReturn(CUSTOMER_PHONE);

        OradianMiddlewareClient oradian = mock(OradianMiddlewareClient.class);
        when(oradian.getDepositsForMsisdn(CUSTOMER_PHONE))
                .thenReturn(List.of(ownedAccount(OWNED_ACCOUNT)));
        when(oradian.submitWithdrawal(any(WithdrawalRequest.class)))
                .thenReturn(WithdrawalResponse.builder().transactionID("ok").build());

        WithdrawalRequest body = WithdrawalRequest.builder()
                .accountID(OWNED_ACCOUNT)
                .paymentMethodName("Cash")
                .amount("10.00")
                .build(); // notes intentionally null

        new TransfersController(jwt, oradian, stubbedTxService())
                .withdraw(bearerRequest(VALID_TOKEN), body);

        ArgumentCaptor<WithdrawalRequest> forwarded = ArgumentCaptor.forClass(WithdrawalRequest.class);
        verify(oradian).submitWithdrawal(forwarded.capture());
        assertEquals("", forwarded.getValue().getNotes());
    }

    // ----- ledger interactions -----

    @Test
    void deposit_writesPendingThenMarksSucceeded_onHappyPath() {
        JwtUtil jwt = mock(JwtUtil.class);
        when(jwt.isTokenValid(VALID_TOKEN)).thenReturn(true);
        when(jwt.extractPhoneNumber(VALID_TOKEN)).thenReturn(CUSTOMER_PHONE);

        OradianMiddlewareClient oradian = mock(OradianMiddlewareClient.class);
        when(oradian.getDepositsForMsisdn(CUSTOMER_PHONE))
                .thenReturn(List.of(ownedAccount(OWNED_ACCOUNT)));
        when(oradian.submitDepositTransfer(any(DepositTransferRequest.class)))
                .thenReturn(DepositTransferResponse.builder()
                        .transactionID("oradian-1155")
                        .referenceNumber("ref-9999")
                        .build());

        TransactionService txService = stubbedTxService();

        new TransfersController(jwt, oradian, txService)
                .transferDeposit(bearerRequest(VALID_TOKEN), request(OWNED_ACCOUNT));

        ArgumentCaptor<Transaction> pending = ArgumentCaptor.forClass(Transaction.class);
        verify(txService).openPending(pending.capture());
        Transaction draft = pending.getValue();
        assertEquals(TransactionType.DEPOSIT, draft.getTransactionType());
        assertEquals(CUSTOMER_PHONE, draft.getCustomerPhone());
        assertEquals(OWNED_ACCOUNT, draft.getSourceAccountId());
        assertEquals(DEST_ACCOUNT, draft.getDestinationAccountId());
        assertEquals(0, new BigDecimal("123.00").compareTo(draft.getAmount()));

        // markSucceeded must be called with the Oradian-assigned IDs from
        // the upstream response — without this the ledger row stays PENDING
        // forever even though Oradian successfully moved the money.
        verify(txService).markSucceeded(eq(draft.getId()), eq("oradian-1155"), eq("ref-9999"), eq(null));
        verify(txService, never()).markFailed(any(), any());
    }

    @Test
    void deposit_writesPendingThenMarksFailed_whenOradianThrows() {
        JwtUtil jwt = mock(JwtUtil.class);
        when(jwt.isTokenValid(VALID_TOKEN)).thenReturn(true);
        when(jwt.extractPhoneNumber(VALID_TOKEN)).thenReturn(CUSTOMER_PHONE);

        OradianMiddlewareClient oradian = mock(OradianMiddlewareClient.class);
        when(oradian.getDepositsForMsisdn(CUSTOMER_PHONE))
                .thenReturn(List.of(ownedAccount(OWNED_ACCOUNT)));
        OradianMiddlewareException upstream =
                new OradianMiddlewareException("Insufficient funds", 422);
        when(oradian.submitDepositTransfer(any(DepositTransferRequest.class)))
                .thenThrow(upstream);

        TransactionService txService = stubbedTxService();

        // Controller re-throws so the GlobalExceptionHandler can map the
        // upstream status into the ApiResult envelope; we expect the
        // exception to propagate but the FAILED ledger write to still happen.
        assertThrows(OradianMiddlewareException.class, () ->
                new TransfersController(jwt, oradian, txService)
                        .transferDeposit(bearerRequest(VALID_TOKEN), request(OWNED_ACCOUNT)));

        ArgumentCaptor<Transaction> pending = ArgumentCaptor.forClass(Transaction.class);
        verify(txService).openPending(pending.capture());
        verify(txService).markFailed(eq(pending.getValue().getId()), eq(upstream));
        verify(txService, never()).markSucceeded(any(), any(), any(), any());
    }

    @Test
    void withdraw_writesPendingThenMarksSucceeded_onHappyPath() {
        JwtUtil jwt = mock(JwtUtil.class);
        when(jwt.isTokenValid(VALID_TOKEN)).thenReturn(true);
        when(jwt.extractPhoneNumber(VALID_TOKEN)).thenReturn(CUSTOMER_PHONE);

        OradianMiddlewareClient oradian = mock(OradianMiddlewareClient.class);
        when(oradian.getDepositsForMsisdn(CUSTOMER_PHONE))
                .thenReturn(List.of(ownedAccount(OWNED_ACCOUNT)));
        when(oradian.submitWithdrawal(any(WithdrawalRequest.class)))
                .thenReturn(WithdrawalResponse.builder()
                        .transactionID("oradian-2233")
                        .referenceNumber("ref-7777")
                        .commandID("cmd-210")
                        .build());

        TransactionService txService = stubbedTxService();

        new TransfersController(jwt, oradian, txService)
                .withdraw(bearerRequest(VALID_TOKEN), withdrawalRequest(OWNED_ACCOUNT));

        ArgumentCaptor<Transaction> pending = ArgumentCaptor.forClass(Transaction.class);
        verify(txService).openPending(pending.capture());
        Transaction draft = pending.getValue();
        assertEquals(TransactionType.WITHDRAWAL, draft.getTransactionType());
        assertEquals(OWNED_ACCOUNT, draft.getSourceAccountId());
        assertNull(draft.getDestinationAccountId(), "withdrawal has no destination account");
        assertEquals("Cash", draft.getPaymentMethodName());
        assertEquals("MobileBanking", draft.getTransactionBranchId());

        verify(txService).markSucceeded(eq(draft.getId()), eq("oradian-2233"), eq("ref-7777"), eq("cmd-210"));
        verify(txService, never()).markFailed(any(), any());
    }

    @Test
    void withdraw_writesPendingThenMarksFailed_whenOradianThrows() {
        JwtUtil jwt = mock(JwtUtil.class);
        when(jwt.isTokenValid(VALID_TOKEN)).thenReturn(true);
        when(jwt.extractPhoneNumber(VALID_TOKEN)).thenReturn(CUSTOMER_PHONE);

        OradianMiddlewareClient oradian = mock(OradianMiddlewareClient.class);
        when(oradian.getDepositsForMsisdn(CUSTOMER_PHONE))
                .thenReturn(List.of(ownedAccount(OWNED_ACCOUNT)));
        OradianMiddlewareException upstream =
                new OradianMiddlewareException("Account suspended", 422);
        when(oradian.submitWithdrawal(any(WithdrawalRequest.class))).thenThrow(upstream);

        TransactionService txService = stubbedTxService();

        assertThrows(OradianMiddlewareException.class, () ->
                new TransfersController(jwt, oradian, txService)
                        .withdraw(bearerRequest(VALID_TOKEN), withdrawalRequest(OWNED_ACCOUNT)));

        ArgumentCaptor<Transaction> pending = ArgumentCaptor.forClass(Transaction.class);
        verify(txService).openPending(pending.capture());
        verify(txService).markFailed(eq(pending.getValue().getId()), eq(upstream));
        verify(txService, never()).markSucceeded(any(), any(), any(), any());
    }

    @Test
    void deposit_returns400_whenAmountIsZero_andDoesNotTouchLedgerOrOradian() {
        JwtUtil jwt = mock(JwtUtil.class);
        when(jwt.isTokenValid(VALID_TOKEN)).thenReturn(true);
        when(jwt.extractPhoneNumber(VALID_TOKEN)).thenReturn(CUSTOMER_PHONE);

        OradianMiddlewareClient oradian = mock(OradianMiddlewareClient.class);
        when(oradian.getDepositsForMsisdn(CUSTOMER_PHONE))
                .thenReturn(List.of(ownedAccount(OWNED_ACCOUNT)));
        TransactionService txService = stubbedTxService();

        DepositTransferRequest body = request(OWNED_ACCOUNT);
        body.setAmount("0.00");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                new TransfersController(jwt, oradian, txService)
                        .transferDeposit(bearerRequest(VALID_TOKEN), body));
        assertTrue(ex.getMessage().contains("greater than zero"));
        // Pre-Oradian validation; nothing must hit the ledger or upstream.
        verify(txService, never()).openPending(any());
        verify(oradian, never()).submitDepositTransfer(any());
    }

    @Test
    void deposit_returns400_whenAmountIsNotParseable() {
        JwtUtil jwt = mock(JwtUtil.class);
        when(jwt.isTokenValid(VALID_TOKEN)).thenReturn(true);
        when(jwt.extractPhoneNumber(VALID_TOKEN)).thenReturn(CUSTOMER_PHONE);

        OradianMiddlewareClient oradian = mock(OradianMiddlewareClient.class);
        when(oradian.getDepositsForMsisdn(CUSTOMER_PHONE))
                .thenReturn(List.of(ownedAccount(OWNED_ACCOUNT)));
        TransactionService txService = stubbedTxService();

        DepositTransferRequest body = request(OWNED_ACCOUNT);
        body.setAmount("ten bucks");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                new TransfersController(jwt, oradian, txService)
                        .transferDeposit(bearerRequest(VALID_TOKEN), body));
        assertTrue(ex.getMessage().contains("valid decimal"));
        verify(txService, never()).openPending(any());
        verify(oradian, never()).submitDepositTransfer(any());
    }

    @Test
    void deposit_returns400_whenAmountHasMoreThanFourDecimalPlaces() {
        JwtUtil jwt = mock(JwtUtil.class);
        when(jwt.isTokenValid(VALID_TOKEN)).thenReturn(true);
        when(jwt.extractPhoneNumber(VALID_TOKEN)).thenReturn(CUSTOMER_PHONE);

        OradianMiddlewareClient oradian = mock(OradianMiddlewareClient.class);
        when(oradian.getDepositsForMsisdn(CUSTOMER_PHONE))
                .thenReturn(List.of(ownedAccount(OWNED_ACCOUNT)));
        TransactionService txService = stubbedTxService();

        DepositTransferRequest body = request(OWNED_ACCOUNT);
        body.setAmount("123.456789");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                new TransfersController(jwt, oradian, txService)
                        .transferDeposit(bearerRequest(VALID_TOKEN), body));
        assertTrue(ex.getMessage().contains("4 decimal places"));
        verify(txService, never()).openPending(any());
    }

    @Test
    void deposit_capturesIdempotencyKeyOnLedgerRow_whenHeaderPresent() {
        JwtUtil jwt = mock(JwtUtil.class);
        when(jwt.isTokenValid(VALID_TOKEN)).thenReturn(true);
        when(jwt.extractPhoneNumber(VALID_TOKEN)).thenReturn(CUSTOMER_PHONE);

        OradianMiddlewareClient oradian = mock(OradianMiddlewareClient.class);
        when(oradian.getDepositsForMsisdn(CUSTOMER_PHONE))
                .thenReturn(List.of(ownedAccount(OWNED_ACCOUNT)));
        when(oradian.submitDepositTransfer(any(DepositTransferRequest.class)))
                .thenReturn(DepositTransferResponse.builder().transactionID("ok").build());

        TransactionService txService = stubbedTxService();

        HttpServletRequest http = mock(HttpServletRequest.class);
        when(http.getHeader("Authorization")).thenReturn("Bearer " + VALID_TOKEN);
        when(http.getHeader("Idempotency-Key")).thenReturn("idem-abc-123");

        new TransfersController(jwt, oradian, txService)
                .transferDeposit(http, request(OWNED_ACCOUNT));

        ArgumentCaptor<Transaction> pending = ArgumentCaptor.forClass(Transaction.class);
        verify(txService).openPending(pending.capture());
        assertEquals("idem-abc-123", pending.getValue().getIdempotencyKey(),
                "ledger row must carry the FE's Idempotency-Key for later replay/dedup audit");
    }
}
