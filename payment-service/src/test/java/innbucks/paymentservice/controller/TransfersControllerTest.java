package innbucks.paymentservice.controller;

import innbucks.paymentservice.client.OradianMiddlewareClient;
import innbucks.paymentservice.client.OradianMiddlewareException;
import innbucks.paymentservice.dto.ApiResult;
import innbucks.paymentservice.dto.DepositAccount;
import innbucks.paymentservice.dto.DepositTransferRequest;
import innbucks.paymentservice.dto.DepositTransferResponse;
import innbucks.paymentservice.dto.TransactionHistoryResponse;
import innbucks.paymentservice.dto.TransactionView;
import innbucks.paymentservice.dto.WithdrawalRequest;
import innbucks.paymentservice.dto.WithdrawalResponse;
import innbucks.paymentservice.entity.Transaction;
import innbucks.paymentservice.entity.TransactionStatus;
import innbucks.paymentservice.entity.TransactionType;
import innbucks.paymentservice.repository.TransactionRepository;
import innbucks.paymentservice.security.JwtUtil;
import innbucks.paymentservice.service.TransactionService;
import innbucks.paymentservice.service.TransferLimitService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
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

    /**
     * Stubbed {@link TransferLimitService} that always passes. Tests
     * exercising the velocity rejection construct their own mock locally
     * and {@code doThrow(...)} on {@code enforce(...)}.
     */
    private static TransferLimitService stubbedLimitService() {
        return mock(TransferLimitService.class);
    }

    /**
     * Stubbed {@link TransactionRepository} that returns an empty page on
     * any history query. Tests that DO want to assert on the repo query
     * args or stage rows construct their own mock locally.
     */
    private static TransactionRepository stubbedTxRepo() {
        TransactionRepository repo = mock(TransactionRepository.class);
        when(repo.findByCustomerPhoneAndTransactionDateBetween(any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        return repo;
    }

    @Test
    void transfer_happyPath_forwardsToOradianAndReturns200() {
        JwtUtil jwt = mock(JwtUtil.class);
        when(jwt.isTokenValid(VALID_TOKEN)).thenReturn(true);
        when(jwt.extractPhoneNumber(VALID_TOKEN)).thenReturn(CUSTOMER_PHONE);
        when(jwt.extractTier(VALID_TOKEN)).thenReturn(2);

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
                new TransfersController(jwt, oradian, stubbedTxService(), stubbedLimitService(), stubbedTxRepo())
                        .transfer(bearerRequest(VALID_TOKEN), request(OWNED_ACCOUNT));

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals("1155", resp.getBody().getData().getTransactionID());
        verify(oradian).submitDepositTransfer(any(DepositTransferRequest.class));
    }

    @Test
    void transfer_coercesNullNotesToEmptyStringBeforeForwardingToOradian() {
        // Oradian Instafin's SubmitDepositAccountTransfer marks `notes` as a
        // required field. With @JsonInclude(NON_NULL) on the DTO, a null
        // notes value would be omitted from the JSON sent upstream and
        // Oradian would 422 the whole request. The controller MUST coerce
        // null -> "" so the field is always present on the wire.
        JwtUtil jwt = mock(JwtUtil.class);
        when(jwt.isTokenValid(VALID_TOKEN)).thenReturn(true);
        when(jwt.extractPhoneNumber(VALID_TOKEN)).thenReturn(CUSTOMER_PHONE);
        when(jwt.extractTier(VALID_TOKEN)).thenReturn(2);

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

        new TransfersController(jwt, oradian, stubbedTxService(), stubbedLimitService(), stubbedTxRepo())
                .transfer(bearerRequest(VALID_TOKEN), body);

        ArgumentCaptor<DepositTransferRequest> forwarded = ArgumentCaptor.forClass(DepositTransferRequest.class);
        verify(oradian).submitDepositTransfer(forwarded.capture());
        assertEquals("", forwarded.getValue().getNotes(),
                "notes must be coerced from null to \"\" before forwarding");
    }

    @Test
    void transfer_keepsCallerSuppliedNotesIntactWhenPresent() {
        JwtUtil jwt = mock(JwtUtil.class);
        when(jwt.isTokenValid(VALID_TOKEN)).thenReturn(true);
        when(jwt.extractPhoneNumber(VALID_TOKEN)).thenReturn(CUSTOMER_PHONE);
        when(jwt.extractTier(VALID_TOKEN)).thenReturn(2);

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

        new TransfersController(jwt, oradian, stubbedTxService(), stubbedLimitService(), stubbedTxRepo())
                .transfer(bearerRequest(VALID_TOKEN), body);

        ArgumentCaptor<DepositTransferRequest> forwarded = ArgumentCaptor.forClass(DepositTransferRequest.class);
        verify(oradian).submitDepositTransfer(forwarded.capture());
        assertEquals("School fees", forwarded.getValue().getNotes());
    }

    @Test
    void transfer_stampsTodayAsTransactionDateBeforeForwardingToOradian() {
        // The DTO marks transactionDate as JsonIgnore on input, but defence-
        // in-depth: even if a malicious client bypassed Jackson and got a date
        // onto the object somehow, the controller MUST overwrite it. This
        // test pins the contract that the value sent downstream is the
        // server's LocalDate.now(), not whatever was on the inbound request.
        JwtUtil jwt = mock(JwtUtil.class);
        when(jwt.isTokenValid(VALID_TOKEN)).thenReturn(true);
        when(jwt.extractPhoneNumber(VALID_TOKEN)).thenReturn(CUSTOMER_PHONE);
        when(jwt.extractTier(VALID_TOKEN)).thenReturn(2);

        OradianMiddlewareClient oradian = mock(OradianMiddlewareClient.class);
        when(oradian.getDepositsForMsisdn(CUSTOMER_PHONE))
                .thenReturn(List.of(ownedAccount(OWNED_ACCOUNT)));
        when(oradian.submitDepositTransfer(any(DepositTransferRequest.class)))
                .thenReturn(DepositTransferResponse.builder().transactionID("ok").build());

        DepositTransferRequest body = request(OWNED_ACCOUNT);
        body.setTransactionDate(LocalDate.of(1999, 1, 1)); // attacker-supplied; must be ignored

        LocalDate before = LocalDate.now();
        new TransfersController(jwt, oradian, stubbedTxService(), stubbedLimitService(), stubbedTxRepo())
                .transfer(bearerRequest(VALID_TOKEN), body);
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
    void transfer_returns401_whenAuthorizationHeaderIsMissing() {
        JwtUtil jwt = mock(JwtUtil.class);
        OradianMiddlewareClient oradian = mock(OradianMiddlewareClient.class);

        HttpServletRequest http = mock(HttpServletRequest.class);
        when(http.getHeader("Authorization")).thenReturn(null);

        ResponseEntity<ApiResult<DepositTransferResponse>> resp =
                new TransfersController(jwt, oradian, stubbedTxService(), stubbedLimitService(), stubbedTxRepo())
                        .transfer(http, request(OWNED_ACCOUNT));

        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
        assertEquals("Missing Bearer token", resp.getBody().getMessage());
        verifyNoInteractions(jwt);
        verifyNoInteractions(oradian);
    }

    @Test
    void transfer_returns401_whenAuthorizationHeaderHasNoBearerPrefix() {
        JwtUtil jwt = mock(JwtUtil.class);
        OradianMiddlewareClient oradian = mock(OradianMiddlewareClient.class);

        HttpServletRequest http = mock(HttpServletRequest.class);
        when(http.getHeader("Authorization")).thenReturn("Basic abc123");

        ResponseEntity<ApiResult<DepositTransferResponse>> resp =
                new TransfersController(jwt, oradian, stubbedTxService(), stubbedLimitService(), stubbedTxRepo())
                        .transfer(http, request(OWNED_ACCOUNT));

        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
        verifyNoInteractions(oradian);
    }

    @Test
    void transfer_returns401_whenTokenSignatureIsInvalid() {
        JwtUtil jwt = mock(JwtUtil.class);
        when(jwt.isTokenValid("bad-token")).thenReturn(false);

        OradianMiddlewareClient oradian = mock(OradianMiddlewareClient.class);

        ResponseEntity<ApiResult<DepositTransferResponse>> resp =
                new TransfersController(jwt, oradian, stubbedTxService(), stubbedLimitService(), stubbedTxRepo())
                        .transfer(bearerRequest("bad-token"), request(OWNED_ACCOUNT));

        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
        assertEquals("Invalid or expired bearer token", resp.getBody().getMessage());
        verifyNoInteractions(oradian);
    }

    @Test
    void transfer_returns401_whenTokenHasNoPhoneNumberClaim() {
        JwtUtil jwt = mock(JwtUtil.class);
        when(jwt.isTokenValid(VALID_TOKEN)).thenReturn(true);
        when(jwt.extractPhoneNumber(VALID_TOKEN)).thenReturn(null);

        OradianMiddlewareClient oradian = mock(OradianMiddlewareClient.class);

        ResponseEntity<ApiResult<DepositTransferResponse>> resp =
                new TransfersController(jwt, oradian, stubbedTxService(), stubbedLimitService(), stubbedTxRepo())
                        .transfer(bearerRequest(VALID_TOKEN), request(OWNED_ACCOUNT));

        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
        assertTrue(resp.getBody().getMessage().contains("phoneNumber claim"));
        verifyNoInteractions(oradian);
    }

    @Test
    void transfer_returns403_whenFromAccountIdIsNotOwnedByCaller() {
        JwtUtil jwt = mock(JwtUtil.class);
        when(jwt.isTokenValid(VALID_TOKEN)).thenReturn(true);
        when(jwt.extractPhoneNumber(VALID_TOKEN)).thenReturn(CUSTOMER_PHONE);
        when(jwt.extractTier(VALID_TOKEN)).thenReturn(2);

        OradianMiddlewareClient oradian = mock(OradianMiddlewareClient.class);
        when(oradian.getDepositsForMsisdn(CUSTOMER_PHONE))
                .thenReturn(List.of(ownedAccount("A000099"), ownedAccount("A000100")));

        ResponseEntity<ApiResult<DepositTransferResponse>> resp =
                new TransfersController(jwt, oradian, stubbedTxService(), stubbedLimitService(), stubbedTxRepo())
                        .transfer(bearerRequest(VALID_TOKEN), request(OWNED_ACCOUNT));

        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
        assertEquals("fromAccountId does not belong to the authenticated customer",
                resp.getBody().getMessage());
        verify(oradian).getDepositsForMsisdn(eq(CUSTOMER_PHONE));
        verify(oradian, never()).submitDepositTransfer(any());
    }

    @Test
    void transfer_returns403_whenCallerHasNoOradianDepositsAtAll() {
        JwtUtil jwt = mock(JwtUtil.class);
        when(jwt.isTokenValid(VALID_TOKEN)).thenReturn(true);
        when(jwt.extractPhoneNumber(VALID_TOKEN)).thenReturn(CUSTOMER_PHONE);
        when(jwt.extractTier(VALID_TOKEN)).thenReturn(2);

        OradianMiddlewareClient oradian = mock(OradianMiddlewareClient.class);
        when(oradian.getDepositsForMsisdn(CUSTOMER_PHONE)).thenReturn(List.of());

        ResponseEntity<ApiResult<DepositTransferResponse>> resp =
                new TransfersController(jwt, oradian, stubbedTxService(), stubbedLimitService(), stubbedTxRepo())
                        .transfer(bearerRequest(VALID_TOKEN), request(OWNED_ACCOUNT));

        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
        verify(oradian, never()).submitDepositTransfer(any());
    }

    @Test
    void transfer_ignoresDepositsWithBlankIdsWhenMatchingOwnership() {
        // Defensive: Oradian sometimes returns rows with an empty `ID` string
        // (placeholder accounts). A caller sending fromAccountId="" must not
        // be allowed to match those — that would let anyone with any valid
        // JWT bypass the ownership check by sending an empty fromAccountId.
        JwtUtil jwt = mock(JwtUtil.class);
        when(jwt.isTokenValid(VALID_TOKEN)).thenReturn(true);
        when(jwt.extractPhoneNumber(VALID_TOKEN)).thenReturn(CUSTOMER_PHONE);
        when(jwt.extractTier(VALID_TOKEN)).thenReturn(2);

        OradianMiddlewareClient oradian = mock(OradianMiddlewareClient.class);
        when(oradian.getDepositsForMsisdn(CUSTOMER_PHONE))
                .thenReturn(List.of(ownedAccount(""), ownedAccount(null)));

        ResponseEntity<ApiResult<DepositTransferResponse>> resp =
                new TransfersController(jwt, oradian, stubbedTxService(), stubbedLimitService(), stubbedTxRepo())
                        .transfer(bearerRequest(VALID_TOKEN), request(""));

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
        when(jwt.extractTier(VALID_TOKEN)).thenReturn(2);

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
                new TransfersController(jwt, oradian, stubbedTxService(), stubbedLimitService(), stubbedTxRepo())
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
                new TransfersController(jwt, oradian, stubbedTxService(), stubbedLimitService(), stubbedTxRepo())
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
                new TransfersController(jwt, oradian, stubbedTxService(), stubbedLimitService(), stubbedTxRepo())
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
                new TransfersController(jwt, oradian, stubbedTxService(), stubbedLimitService(), stubbedTxRepo())
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
        when(jwt.extractTier(VALID_TOKEN)).thenReturn(2);

        OradianMiddlewareClient oradian = mock(OradianMiddlewareClient.class);
        when(oradian.getDepositsForMsisdn(CUSTOMER_PHONE))
                .thenReturn(List.of(ownedAccount("A000099"), ownedAccount("A000100")));

        ResponseEntity<ApiResult<WithdrawalResponse>> resp =
                new TransfersController(jwt, oradian, stubbedTxService(), stubbedLimitService(), stubbedTxRepo())
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
        when(jwt.extractTier(VALID_TOKEN)).thenReturn(2);

        OradianMiddlewareClient oradian = mock(OradianMiddlewareClient.class);
        when(oradian.getDepositsForMsisdn(CUSTOMER_PHONE))
                .thenReturn(List.of(ownedAccount(OWNED_ACCOUNT)));
        when(oradian.submitWithdrawal(any(WithdrawalRequest.class)))
                .thenReturn(WithdrawalResponse.builder().transactionID("ok").build());

        WithdrawalRequest body = withdrawalRequest(OWNED_ACCOUNT);
        body.setTransactionDate(LocalDate.of(1999, 1, 1));
        body.setTransactionBranchID("HeadOffice");
        body.setOverrideLimitCheck(true);

        new TransfersController(jwt, oradian, stubbedTxService(), stubbedLimitService(), stubbedTxRepo())
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
        when(jwt.extractTier(VALID_TOKEN)).thenReturn(2);

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

        new TransfersController(jwt, oradian, stubbedTxService(), stubbedLimitService(), stubbedTxRepo())
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
        when(jwt.extractTier(VALID_TOKEN)).thenReturn(2);

        OradianMiddlewareClient oradian = mock(OradianMiddlewareClient.class);
        when(oradian.getDepositsForMsisdn(CUSTOMER_PHONE))
                .thenReturn(List.of(ownedAccount(OWNED_ACCOUNT)));
        when(oradian.submitDepositTransfer(any(DepositTransferRequest.class)))
                .thenReturn(DepositTransferResponse.builder()
                        .transactionID("oradian-1155")
                        .referenceNumber("ref-9999")
                        .build());

        TransactionService txService = stubbedTxService();

        new TransfersController(jwt, oradian, txService, stubbedLimitService(), stubbedTxRepo())
                .transfer(bearerRequest(VALID_TOKEN), request(OWNED_ACCOUNT));

        ArgumentCaptor<Transaction> pending = ArgumentCaptor.forClass(Transaction.class);
        verify(txService).openPending(pending.capture());
        Transaction draft = pending.getValue();
        assertEquals(TransactionType.TRANSFER, draft.getTransactionType());
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
        when(jwt.extractTier(VALID_TOKEN)).thenReturn(2);

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
                new TransfersController(jwt, oradian, txService, stubbedLimitService(), stubbedTxRepo())
                        .transfer(bearerRequest(VALID_TOKEN), request(OWNED_ACCOUNT)));

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
        when(jwt.extractTier(VALID_TOKEN)).thenReturn(2);

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

        new TransfersController(jwt, oradian, txService, stubbedLimitService(), stubbedTxRepo())
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
        when(jwt.extractTier(VALID_TOKEN)).thenReturn(2);

        OradianMiddlewareClient oradian = mock(OradianMiddlewareClient.class);
        when(oradian.getDepositsForMsisdn(CUSTOMER_PHONE))
                .thenReturn(List.of(ownedAccount(OWNED_ACCOUNT)));
        OradianMiddlewareException upstream =
                new OradianMiddlewareException("Account suspended", 422);
        when(oradian.submitWithdrawal(any(WithdrawalRequest.class))).thenThrow(upstream);

        TransactionService txService = stubbedTxService();

        assertThrows(OradianMiddlewareException.class, () ->
                new TransfersController(jwt, oradian, txService, stubbedLimitService(), stubbedTxRepo())
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
        when(jwt.extractTier(VALID_TOKEN)).thenReturn(2);

        OradianMiddlewareClient oradian = mock(OradianMiddlewareClient.class);
        when(oradian.getDepositsForMsisdn(CUSTOMER_PHONE))
                .thenReturn(List.of(ownedAccount(OWNED_ACCOUNT)));
        TransactionService txService = stubbedTxService();

        DepositTransferRequest body = request(OWNED_ACCOUNT);
        body.setAmount("0.00");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                new TransfersController(jwt, oradian, txService, stubbedLimitService(), stubbedTxRepo())
                        .transfer(bearerRequest(VALID_TOKEN), body));
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
        when(jwt.extractTier(VALID_TOKEN)).thenReturn(2);

        OradianMiddlewareClient oradian = mock(OradianMiddlewareClient.class);
        when(oradian.getDepositsForMsisdn(CUSTOMER_PHONE))
                .thenReturn(List.of(ownedAccount(OWNED_ACCOUNT)));
        TransactionService txService = stubbedTxService();

        DepositTransferRequest body = request(OWNED_ACCOUNT);
        body.setAmount("ten bucks");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                new TransfersController(jwt, oradian, txService, stubbedLimitService(), stubbedTxRepo())
                        .transfer(bearerRequest(VALID_TOKEN), body));
        assertTrue(ex.getMessage().contains("valid decimal"));
        verify(txService, never()).openPending(any());
        verify(oradian, never()).submitDepositTransfer(any());
    }

    @Test
    void deposit_returns400_whenAmountHasMoreThanFourDecimalPlaces() {
        JwtUtil jwt = mock(JwtUtil.class);
        when(jwt.isTokenValid(VALID_TOKEN)).thenReturn(true);
        when(jwt.extractPhoneNumber(VALID_TOKEN)).thenReturn(CUSTOMER_PHONE);
        when(jwt.extractTier(VALID_TOKEN)).thenReturn(2);

        OradianMiddlewareClient oradian = mock(OradianMiddlewareClient.class);
        when(oradian.getDepositsForMsisdn(CUSTOMER_PHONE))
                .thenReturn(List.of(ownedAccount(OWNED_ACCOUNT)));
        TransactionService txService = stubbedTxService();

        DepositTransferRequest body = request(OWNED_ACCOUNT);
        body.setAmount("123.456789");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                new TransfersController(jwt, oradian, txService, stubbedLimitService(), stubbedTxRepo())
                        .transfer(bearerRequest(VALID_TOKEN), body));
        assertTrue(ex.getMessage().contains("4 decimal places"));
        verify(txService, never()).openPending(any());
    }

    @Test
    void deposit_capturesIdempotencyKeyOnLedgerRow_whenHeaderPresent() {
        JwtUtil jwt = mock(JwtUtil.class);
        when(jwt.isTokenValid(VALID_TOKEN)).thenReturn(true);
        when(jwt.extractPhoneNumber(VALID_TOKEN)).thenReturn(CUSTOMER_PHONE);
        when(jwt.extractTier(VALID_TOKEN)).thenReturn(2);

        OradianMiddlewareClient oradian = mock(OradianMiddlewareClient.class);
        when(oradian.getDepositsForMsisdn(CUSTOMER_PHONE))
                .thenReturn(List.of(ownedAccount(OWNED_ACCOUNT)));
        when(oradian.submitDepositTransfer(any(DepositTransferRequest.class)))
                .thenReturn(DepositTransferResponse.builder().transactionID("ok").build());

        TransactionService txService = stubbedTxService();

        HttpServletRequest http = mock(HttpServletRequest.class);
        when(http.getHeader("Authorization")).thenReturn("Bearer " + VALID_TOKEN);
        when(http.getHeader("Idempotency-Key")).thenReturn("idem-abc-123");

        new TransfersController(jwt, oradian, txService, stubbedLimitService(), stubbedTxRepo())
                .transfer(http, request(OWNED_ACCOUNT));

        ArgumentCaptor<Transaction> pending = ArgumentCaptor.forClass(Transaction.class);
        verify(txService).openPending(pending.capture());
        assertEquals("idem-abc-123", pending.getValue().getIdempotencyKey(),
                "ledger row must carry the FE's Idempotency-Key for later replay/dedup audit");
    }

    // ----- KYC tier + account status gates -----

    @Test
    void transfer_returns403_whenTierIsBelow2() {
        JwtUtil jwt = mock(JwtUtil.class);
        when(jwt.isTokenValid(VALID_TOKEN)).thenReturn(true);
        when(jwt.extractPhoneNumber(VALID_TOKEN)).thenReturn(CUSTOMER_PHONE);
        when(jwt.extractTier(VALID_TOKEN)).thenReturn(1); // tier-1: no Oradian account

        OradianMiddlewareClient oradian = mock(OradianMiddlewareClient.class);

        ResponseEntity<ApiResult<DepositTransferResponse>> resp =
                new TransfersController(jwt, oradian, stubbedTxService(), stubbedLimitService(), stubbedTxRepo())
                        .transfer(bearerRequest(VALID_TOKEN), request(OWNED_ACCOUNT));

        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
        assertTrue(resp.getBody().getMessage().contains("tier 2"),
                "message must explain the tier requirement");
        // Reject BEFORE the deposits lookup — no point burning an Oradian
        // round-trip for a customer who definitionally has no accounts.
        verifyNoInteractions(oradian);
    }

    @Test
    void transfer_returns403_whenJwtHasNoTierClaim() {
        // Staff tokens (MERCHANT_ADMIN / SHOP_ADMIN) don't carry a tier
        // claim — they shouldn't be hitting customer money endpoints at
        // all, but if one ever does (mis-routed test traffic, etc.) it
        // must be rejected at the gate.
        JwtUtil jwt = mock(JwtUtil.class);
        when(jwt.isTokenValid(VALID_TOKEN)).thenReturn(true);
        when(jwt.extractPhoneNumber(VALID_TOKEN)).thenReturn(CUSTOMER_PHONE);
        when(jwt.extractTier(VALID_TOKEN)).thenReturn(null);

        OradianMiddlewareClient oradian = mock(OradianMiddlewareClient.class);

        ResponseEntity<ApiResult<DepositTransferResponse>> resp =
                new TransfersController(jwt, oradian, stubbedTxService(), stubbedLimitService(), stubbedTxRepo())
                        .transfer(bearerRequest(VALID_TOKEN), request(OWNED_ACCOUNT));

        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
        verifyNoInteractions(oradian);
    }

    @Test
    void transfer_returns403_whenSourceAccountStatusIsNotActive() {
        JwtUtil jwt = mock(JwtUtil.class);
        when(jwt.isTokenValid(VALID_TOKEN)).thenReturn(true);
        when(jwt.extractPhoneNumber(VALID_TOKEN)).thenReturn(CUSTOMER_PHONE);
        when(jwt.extractTier(VALID_TOKEN)).thenReturn(2);

        OradianMiddlewareClient oradian = mock(OradianMiddlewareClient.class);
        // Owned, but status=Frozen — must be rejected before the Oradian
        // transfer call. (Oradian would refuse anyway, but with a generic
        // 4xx that doesn't tell the FE the precise reason.)
        DepositAccount frozen = DepositAccount.builder().ID(OWNED_ACCOUNT).status("Frozen").build();
        when(oradian.getDepositsForMsisdn(CUSTOMER_PHONE)).thenReturn(List.of(frozen));

        ResponseEntity<ApiResult<DepositTransferResponse>> resp =
                new TransfersController(jwt, oradian, stubbedTxService(), stubbedLimitService(), stubbedTxRepo())
                        .transfer(bearerRequest(VALID_TOKEN), request(OWNED_ACCOUNT));

        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
        assertTrue(resp.getBody().getMessage().contains("Frozen"),
                "message should leak the upstream status so the FE can render \"account frozen\" UX");
        verify(oradian, never()).submitDepositTransfer(any());
    }

    @Test
    void withdraw_returns403_whenTierIsBelow2() {
        JwtUtil jwt = mock(JwtUtil.class);
        when(jwt.isTokenValid(VALID_TOKEN)).thenReturn(true);
        when(jwt.extractPhoneNumber(VALID_TOKEN)).thenReturn(CUSTOMER_PHONE);
        when(jwt.extractTier(VALID_TOKEN)).thenReturn(1);

        OradianMiddlewareClient oradian = mock(OradianMiddlewareClient.class);

        ResponseEntity<ApiResult<WithdrawalResponse>> resp =
                new TransfersController(jwt, oradian, stubbedTxService(), stubbedLimitService(), stubbedTxRepo())
                        .withdraw(bearerRequest(VALID_TOKEN), withdrawalRequest(OWNED_ACCOUNT));

        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
        assertTrue(resp.getBody().getMessage().contains("tier 2"));
        verifyNoInteractions(oradian);
    }

    // ----- Velocity / daily caps -----

    @Test
    void transfer_propagatesLimitServiceRejection_andDoesNotTouchLedgerOrOradian() {
        JwtUtil jwt = mock(JwtUtil.class);
        when(jwt.isTokenValid(VALID_TOKEN)).thenReturn(true);
        when(jwt.extractPhoneNumber(VALID_TOKEN)).thenReturn(CUSTOMER_PHONE);
        when(jwt.extractTier(VALID_TOKEN)).thenReturn(2);

        OradianMiddlewareClient oradian = mock(OradianMiddlewareClient.class);
        when(oradian.getDepositsForMsisdn(CUSTOMER_PHONE))
                .thenReturn(List.of(ownedAccount(OWNED_ACCOUNT)));

        TransactionService txService = stubbedTxService();
        TransferLimitService limits = mock(TransferLimitService.class);
        doThrow(new IllegalArgumentException(
                "Daily limit exceeded (max 500000, today 450000, requested 100000, projected 550000)"))
                .when(limits).enforce(eq(OWNED_ACCOUNT), any(BigDecimal.class));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                new TransfersController(jwt, oradian, txService, limits, stubbedTxRepo())
                        .transfer(bearerRequest(VALID_TOKEN), request(OWNED_ACCOUNT)));
        assertTrue(ex.getMessage().contains("Daily limit exceeded"));

        // Pre-Oradian, pre-ledger gate: the rejection must NOT leave a row
        // in the transactions table (would be misleading reconciliation
        // signal) AND must NOT have called Oradian (no real-money side
        // effect from a policy violation).
        verify(txService, never()).openPending(any());
        verify(oradian, never()).submitDepositTransfer(any());
    }

    @Test
    void withdraw_propagatesLimitServiceRejection_andDoesNotTouchLedgerOrOradian() {
        JwtUtil jwt = mock(JwtUtil.class);
        when(jwt.isTokenValid(VALID_TOKEN)).thenReturn(true);
        when(jwt.extractPhoneNumber(VALID_TOKEN)).thenReturn(CUSTOMER_PHONE);
        when(jwt.extractTier(VALID_TOKEN)).thenReturn(2);

        OradianMiddlewareClient oradian = mock(OradianMiddlewareClient.class);
        when(oradian.getDepositsForMsisdn(CUSTOMER_PHONE))
                .thenReturn(List.of(ownedAccount(OWNED_ACCOUNT)));

        TransactionService txService = stubbedTxService();
        TransferLimitService limits = mock(TransferLimitService.class);
        doThrow(new IllegalArgumentException(
                "Per-transaction limit exceeded (max 100000, requested 1000000)"))
                .when(limits).enforce(eq(OWNED_ACCOUNT), any(BigDecimal.class));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                new TransfersController(jwt, oradian, txService, limits, stubbedTxRepo())
                        .withdraw(bearerRequest(VALID_TOKEN), withdrawalRequest(OWNED_ACCOUNT)));
        assertTrue(ex.getMessage().contains("Per-transaction limit exceeded"));

        verify(txService, never()).openPending(any());
        verify(oradian, never()).submitWithdrawal(any());
    }

    @Test
    void withdraw_returns403_whenSourceAccountStatusIsNotActive() {
        JwtUtil jwt = mock(JwtUtil.class);
        when(jwt.isTokenValid(VALID_TOKEN)).thenReturn(true);
        when(jwt.extractPhoneNumber(VALID_TOKEN)).thenReturn(CUSTOMER_PHONE);
        when(jwt.extractTier(VALID_TOKEN)).thenReturn(2);

        OradianMiddlewareClient oradian = mock(OradianMiddlewareClient.class);
        DepositAccount closed = DepositAccount.builder().ID(OWNED_ACCOUNT).status("Closed").build();
        when(oradian.getDepositsForMsisdn(CUSTOMER_PHONE)).thenReturn(List.of(closed));

        ResponseEntity<ApiResult<WithdrawalResponse>> resp =
                new TransfersController(jwt, oradian, stubbedTxService(), stubbedLimitService(), stubbedTxRepo())
                        .withdraw(bearerRequest(VALID_TOKEN), withdrawalRequest(OWNED_ACCOUNT));

        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
        assertTrue(resp.getBody().getMessage().contains("Closed"));
        verify(oradian, never()).submitWithdrawal(any());
    }

    // ----- GET /payments/transactions (history endpoint) -----

    private static Transaction ledgerRow(TransactionType type, TransactionStatus status, String amount) {
        return Transaction.builder()
                .id(UUID.randomUUID())
                .transactionType(type)
                .status(status)
                .customerPhone(CUSTOMER_PHONE)
                .sourceAccountId(OWNED_ACCOUNT)
                .destinationAccountId(DEST_ACCOUNT)
                .amount(new BigDecimal(amount))
                .transactionDate(LocalDate.of(2026, 5, 18))
                .build();
    }

    @Test
    void listTransactions_returnsCallersRowsWithSensitiveFieldsStripped() {
        JwtUtil jwt = mock(JwtUtil.class);
        when(jwt.isTokenValid(VALID_TOKEN)).thenReturn(true);
        when(jwt.extractPhoneNumber(VALID_TOKEN)).thenReturn(CUSTOMER_PHONE);

        Transaction row = ledgerRow(TransactionType.TRANSFER, TransactionStatus.SUCCEEDED, "100.00");
        row.setIdempotencyKey("idem-secret-key");
        row.setCorrelationId("trace-xyz");
        row.setOradianCommandId("cmd-99");
        row.setOradianTransactionId("oradian-1");
        row.setOradianReferenceNumber("ref-1");

        TransactionRepository repo = mock(TransactionRepository.class);
        when(repo.findByCustomerPhone(eq(CUSTOMER_PHONE), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(row), PageRequest.of(0, 10), 1));

        ResponseEntity<ApiResult<TransactionHistoryResponse>> resp =
                new TransfersController(jwt, mock(OradianMiddlewareClient.class), stubbedTxService(),
                        stubbedLimitService(), repo)
                        .listTransactions(bearerRequest(VALID_TOKEN), null);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        TransactionHistoryResponse data = resp.getBody().getData();
        assertEquals(1, data.totalElements());
        var view = data.transactions().get(0);
        assertEquals("TRANSFER", view.type());
        assertEquals("SUCCEEDED", view.status());
        assertEquals("oradian-1", view.oradianTransactionId(),
                "customer-relevant Oradian IDs must be exposed");
        // No idempotencyKey / correlationId / oradianCommandId fields exist
        // on TransactionView; presence is impossible by construction. This
        // assertion is structural — if someone adds them to the record,
        // it'd surface in code review (test compile catches the rename).
        // We also pin that the serialised JSON-shape doesn't carry them.
        String json;
        try {
            // findAndRegisterModules picks up jackson-datatype-jsr310 from
            // the classpath so LocalDate / Instant serialise cleanly. Boot's
            // production ObjectMapper does this automatically; the test
            // builds a throwaway mapper, so we wire the module manually.
            json = new com.fasterxml.jackson.databind.ObjectMapper()
                    .findAndRegisterModules()
                    .writeValueAsString(view);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        assertFalse(json.contains("idem-secret-key"),
                "idempotencyKey must not leak into the customer-facing history view");
        assertFalse(json.contains("trace-xyz"),
                "correlationId is a server-side trace key; must not be in the response");
        assertFalse(json.contains("cmd-99"),
                "oradianCommandId is an internal upstream field; must not be in the response");
    }

    @Test
    void listTransactions_defaults_alwaysReturnsLatest10NewestFirst() {
        // No fromDate/toDate/page/size on the endpoint anymore — every call
        // pulls page 0, size 10, sorted createdAt DESC. Lock that in so a
        // future "let's bring pagination back" change is caught here.
        JwtUtil jwt = mock(JwtUtil.class);
        when(jwt.isTokenValid(VALID_TOKEN)).thenReturn(true);
        when(jwt.extractPhoneNumber(VALID_TOKEN)).thenReturn(CUSTOMER_PHONE);

        TransactionRepository repo = mock(TransactionRepository.class);
        when(repo.findByCustomerPhone(any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        new TransfersController(jwt, mock(OradianMiddlewareClient.class), stubbedTxService(),
                stubbedLimitService(), repo)
                .listTransactions(bearerRequest(VALID_TOKEN), null);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(repo).findByCustomerPhone(eq(CUSTOMER_PHONE), pageable.capture());
        verify(repo, never()).findByCustomerPhoneAndType(any(), any(), any());
        assertEquals(0, pageable.getValue().getPageNumber());
        assertEquals(10, pageable.getValue().getPageSize());
        var sortOrder = pageable.getValue().getSort().getOrderFor("createdAt");
        assertEquals(org.springframework.data.domain.Sort.Direction.DESC, sortOrder.getDirection());
    }

    @Test
    void listTransactions_typeFilter_routesToTypeFilteredRepo() {
        // ?type=WITHDRAWAL must call the type-filtered finder, not the
        // unfiltered one. Guards the routing in the controller's if/else.
        JwtUtil jwt = mock(JwtUtil.class);
        when(jwt.isTokenValid(VALID_TOKEN)).thenReturn(true);
        when(jwt.extractPhoneNumber(VALID_TOKEN)).thenReturn(CUSTOMER_PHONE);

        TransactionRepository repo = mock(TransactionRepository.class);
        when(repo.findByCustomerPhoneAndType(any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        new TransfersController(jwt, mock(OradianMiddlewareClient.class), stubbedTxService(),
                stubbedLimitService(), repo)
                .listTransactions(bearerRequest(VALID_TOKEN), TransactionType.WITHDRAWAL);

        verify(repo).findByCustomerPhoneAndType(
                eq(CUSTOMER_PHONE), eq(TransactionType.WITHDRAWAL), any(Pageable.class));
        verify(repo, never()).findByCustomerPhone(any(), any(Pageable.class));
    }

    @Test
    void listTransactions_returns401_whenAuthorizationHeaderIsMissing() {
        TransactionRepository repo = mock(TransactionRepository.class);
        TransfersController ctrl = new TransfersController(mock(JwtUtil.class),
                mock(OradianMiddlewareClient.class), stubbedTxService(),
                stubbedLimitService(), repo);

        HttpServletRequest http = mock(HttpServletRequest.class);
        when(http.getHeader("Authorization")).thenReturn(null);

        ResponseEntity<ApiResult<TransactionHistoryResponse>> resp =
                ctrl.listTransactions(http, null);

        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
        verifyNoInteractions(repo);
    }

    @Test
    void listTransactions_returns401_whenTokenHasNoPhoneNumberClaim() {
        JwtUtil jwt = mock(JwtUtil.class);
        when(jwt.isTokenValid(VALID_TOKEN)).thenReturn(true);
        when(jwt.extractPhoneNumber(VALID_TOKEN)).thenReturn(null);

        TransactionRepository repo = mock(TransactionRepository.class);

        ResponseEntity<ApiResult<TransactionHistoryResponse>> resp =
                new TransfersController(jwt, mock(OradianMiddlewareClient.class),
                        stubbedTxService(), stubbedLimitService(), repo)
                        .listTransactions(bearerRequest(VALID_TOKEN), null);

        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
        verifyNoInteractions(repo);
    }

    // ----- GET /payments/transactions/{id} (detail endpoint) -----

    @Test
    void getTransaction_returnsRowOwnedByCaller() {
        JwtUtil jwt = mock(JwtUtil.class);
        when(jwt.isTokenValid(VALID_TOKEN)).thenReturn(true);
        when(jwt.extractPhoneNumber(VALID_TOKEN)).thenReturn(CUSTOMER_PHONE);

        Transaction row = ledgerRow(TransactionType.WITHDRAWAL, TransactionStatus.SUCCEEDED, "75.00");
        row.setOradianTransactionId("oradian-receipt-1");

        TransactionRepository repo = mock(TransactionRepository.class);
        when(repo.findById(row.getId())).thenReturn(Optional.of(row));

        ResponseEntity<ApiResult<TransactionView>> resp =
                new TransfersController(jwt, mock(OradianMiddlewareClient.class), stubbedTxService(),
                        stubbedLimitService(), repo)
                        .getTransaction(bearerRequest(VALID_TOKEN), row.getId());

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(row.getId(), resp.getBody().getData().id());
        assertEquals("oradian-receipt-1", resp.getBody().getData().oradianTransactionId());
    }

    @Test
    void getTransaction_returns404_whenIdDoesNotExist() {
        JwtUtil jwt = mock(JwtUtil.class);
        when(jwt.isTokenValid(VALID_TOKEN)).thenReturn(true);
        when(jwt.extractPhoneNumber(VALID_TOKEN)).thenReturn(CUSTOMER_PHONE);

        TransactionRepository repo = mock(TransactionRepository.class);
        UUID missingId = UUID.randomUUID();
        when(repo.findById(missingId)).thenReturn(Optional.empty());

        ResponseEntity<ApiResult<TransactionView>> resp =
                new TransfersController(jwt, mock(OradianMiddlewareClient.class), stubbedTxService(),
                        stubbedLimitService(), repo)
                        .getTransaction(bearerRequest(VALID_TOKEN), missingId);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
        assertEquals("Transaction not found", resp.getBody().getMessage());
    }

    @Test
    void getTransaction_returns404_whenRowBelongsToDifferentCustomer() {
        // Defence against UUID-probing: existence is NOT leaked. A caller
        // who guesses a real txId but doesn't own it gets the same 404
        // shape as "doesn't exist" — they can't distinguish the two.
        JwtUtil jwt = mock(JwtUtil.class);
        when(jwt.isTokenValid(VALID_TOKEN)).thenReturn(true);
        when(jwt.extractPhoneNumber(VALID_TOKEN)).thenReturn(CUSTOMER_PHONE);

        Transaction othersRow = ledgerRow(TransactionType.TRANSFER, TransactionStatus.SUCCEEDED, "100.00");
        othersRow.setCustomerPhone("+254700000999"); // not CUSTOMER_PHONE

        TransactionRepository repo = mock(TransactionRepository.class);
        when(repo.findById(othersRow.getId())).thenReturn(Optional.of(othersRow));

        ResponseEntity<ApiResult<TransactionView>> resp =
                new TransfersController(jwt, mock(OradianMiddlewareClient.class), stubbedTxService(),
                        stubbedLimitService(), repo)
                        .getTransaction(bearerRequest(VALID_TOKEN), othersRow.getId());

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
        assertEquals("Transaction not found", resp.getBody().getMessage(),
                "ownership mismatch must surface as the same 404 shape as 'no such row' so " +
                        "a UUID-probing caller can't distinguish");
    }

    @Test
    void getTransaction_returns401_whenAuthorizationHeaderIsMissing() {
        TransactionRepository repo = mock(TransactionRepository.class);
        TransfersController ctrl = new TransfersController(mock(JwtUtil.class),
                mock(OradianMiddlewareClient.class), stubbedTxService(),
                stubbedLimitService(), repo);

        HttpServletRequest http = mock(HttpServletRequest.class);
        when(http.getHeader("Authorization")).thenReturn(null);

        ResponseEntity<ApiResult<TransactionView>> resp =
                ctrl.getTransaction(http, UUID.randomUUID());

        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
        verifyNoInteractions(repo);
    }
}
