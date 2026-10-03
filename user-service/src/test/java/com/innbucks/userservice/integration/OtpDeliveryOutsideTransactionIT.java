package com.innbucks.userservice.integration;

import com.innbucks.userservice.client.NotificationDeliveryException;
import com.innbucks.userservice.client.SmsNotificationClient;
import com.innbucks.userservice.client.WhatsAppNotificationClient;
import com.innbucks.userservice.dto.CustomerTier1RegisterDTO;
import com.innbucks.userservice.security.OtpHasher;
import com.innbucks.userservice.service.CustomerService;
import com.innbucks.userservice.service.OtpService;
import com.innbucks.userservice.testsupport.PostgresIntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OTP delivery and the loyalty webhook run with NO database transaction open,
 * against real Postgres transactions.
 *
 * <p>The send used to be one {@code @Transactional} method: the quota row and
 * the new code stayed locked for the whole SMS attempt and WhatsApp fallback
 * (past a minute, worst case, on a public endpoint), and its rollback was what
 * gave a customer a clean retry when delivery failed. These pin both halves of
 * the replacement: while the message is being sent the rows are COMMITTED and
 * UNLOCKED (probed from a second connection with {@code FOR UPDATE NOWAIT}),
 * and a failed delivery still leaves exactly what the rollback left — the
 * previous code verifiable, with its failed attempts, and the request counter
 * unchanged.
 */
class OtpDeliveryOutsideTransactionIT extends PostgresIntegrationTestBase {

    @MockitoBean SmsNotificationClient sms;
    @MockitoBean WhatsAppNotificationClient whatsApp;
    @MockitoBean LoyaltyServiceClient loyalty;

    @Autowired OtpService otpService;
    @Autowired CustomerService customerService;
    @Autowired OtpHasher otpHasher;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void resetMocks() {
        reset(sms, whatsApp, loyalty);
    }

    private static String phone() {
        return "+26377" + (1_000_000 + ThreadLocalRandom.current().nextInt(8_999_999));
    }

    private static String codeIn(String message) {
        return message.replaceAll(".*code is (\\d{6}).*", "$1");
    }

    /** Runs on ANOTHER thread, so it gets its own connection, never the caller's transaction. */
    private static <T> T fromAnotherConnection(Supplier<T> query) {
        return CompletableFuture.supplyAsync(query).join();
    }

    /** True when no other transaction holds a lock on the phone's row in {@code table}. */
    private boolean rowIsUnlocked(String table, String key) {
        return fromAnotherConnection(() -> {
            try {
                jdbc.queryForList("SELECT 1 FROM " + table + " WHERE phone_number = ? FOR UPDATE NOWAIT", key);
                return true;
            } catch (org.springframework.dao.DataAccessException lockNotAvailable) {
                return false;
            }
        });
    }

    private Integer attemptCount(String key) {
        List<Integer> rows = jdbc.queryForList(
                "SELECT attempt_count FROM otp_retry_attempts WHERE phone_number = ?", Integer.class, key);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> otpRow(String key) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT code, failed_attempts FROM otps WHERE phone_number = ?", key);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String sendAndCapture(String key) {
        clearInvocations(sms);
        otpService.sendOtp(key);
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(sms).sendSms(eq(key), message.capture(), anyString());
        return codeIn(message.getValue());
    }

    @Test
    @DisplayName("while the SMS is in flight: no transaction on the thread, and the code and counter are committed and unlocked")
    void nothingIsHeldDuringDelivery() {
        String key = phone();
        List<Object> seen = new ArrayList<>();
        doAnswer(inv -> {
            seen.add(TransactionSynchronizationManager.isActualTransactionActive());
            seen.add(fromAnotherConnection(() -> otpRow(key) != null));
            seen.add(fromAnotherConnection(() -> attemptCount(key)));
            seen.add(rowIsUnlocked("otps", key));
            seen.add(rowIsUnlocked("otp_retry_attempts", key));
            return null;
        }).when(sms).sendSms(eq(key), anyString(), anyString());

        otpService.sendOtp(key);

        // no transaction, row committed, counter committed, neither row locked
        assertThat(seen).containsExactly(false, true, 1, true, true);
    }

    @Test
    @DisplayName("the WhatsApp fallback runs with nothing held either")
    void nothingIsHeldDuringTheFallback() {
        String key = phone();
        doThrow(new NotificationDeliveryException("SMS gateway down")).when(sms).sendSms(anyString(), anyString(), anyString());
        List<Object> seen = new ArrayList<>();
        doAnswer(inv -> {
            seen.add(TransactionSynchronizationManager.isActualTransactionActive());
            seen.add(rowIsUnlocked("otps", key));
            seen.add(rowIsUnlocked("otp_retry_attempts", key));
            return null;
        }).when(whatsApp).sendCustomNotification(eq(key), anyString());

        otpService.sendOtp(key);

        assertThat(seen).containsExactly(false, true, true);
        assertThat(otpRow(key)).isNotNull();
    }

    @Test
    @DisplayName("a failed delivery leaves the PREVIOUS code verifiable, with its failed attempts, and the counter unchanged")
    void failedDeliveryLeavesThePreviousCodeWorking() {
        String key = phone();
        String first = sendAndCapture(key);
        String wrong = first.equals("000000") ? "000001" : "000000";
        assertThat(otpService.verifyOtp(key, wrong)).isFalse();      // one failed attempt on the first code
        assertThat(attemptCount(key)).isEqualTo(1);
        assertThat(otpRow(key)).containsEntry("failed_attempts", 1);

        doThrow(new NotificationDeliveryException("SMS gateway down")).when(sms).sendSms(anyString(), anyString(), anyString());
        doThrow(new NotificationDeliveryException("WhatsApp down")).when(whatsApp).sendCustomNotification(anyString(), anyString());
        assertThatThrownBy(() -> otpService.sendOtp(key)).isInstanceOf(NotificationDeliveryException.class);

        assertThat(attemptCount(key)).isEqualTo(1);
        Map<String, Object> row = otpRow(key);
        assertThat(row).containsEntry("code", otpHasher.hash(first)).containsEntry("failed_attempts", 1);

        assertThat(otpService.verifyOtp(key, first)).isTrue();
        assertThat(otpRow(key)).isNull();
        assertThat(attemptCount(key)).isNull();                    // a successful verify clears the counter
    }

    @Test
    @DisplayName("a first-ever send that cannot be delivered leaves no code and no counter")
    void failedFirstSendLeavesNothing() {
        String key = phone();
        doThrow(new NotificationDeliveryException("SMS gateway down")).when(sms).sendSms(anyString(), anyString(), anyString());
        doThrow(new NotificationDeliveryException("WhatsApp down")).when(whatsApp).sendCustomNotification(anyString(), anyString());

        assertThatThrownBy(() -> otpService.sendOtp(key)).isInstanceOf(NotificationDeliveryException.class);

        assertThat(otpRow(key)).isNull();
        assertThat(attemptCount(key)).isNull();
    }

    @Test
    @DisplayName("verify: the account is committed before loyalty is told, and loyalty is told with no transaction open")
    void loyaltyIsToldAfterTheAccountCommits() {
        String key = phone();
        CustomerTier1RegisterDTO tier1 = new CustomerTier1RegisterDTO();
        tier1.setPhoneNumber(key);
        tier1.setPassword("S3cur3Pass!");
        customerService.registerTier1(tier1);
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(sms).sendSms(eq(key), message.capture(), anyString());

        List<Object> seen = new ArrayList<>();
        when(loyalty.promoteUserByPhone(key)).thenAnswer(inv -> {
            seen.add(TransactionSynchronizationManager.isActualTransactionActive());
            seen.add(fromAnotherConnection(() -> jdbc.queryForObject(
                    "SELECT count(*) FROM users WHERE phone_number = ?", Integer.class, key)));
            seen.add(fromAnotherConnection(() -> jdbc.queryForObject(
                    "SELECT count(*) FROM pending_registrations WHERE phone_number = ?", Integer.class, key)));
            seen.add(rowIsUnlocked("users", key));
            return true;
        });

        assertThat(otpService.verifyOtp(key, codeIn(message.getValue()))).isTrue();

        assertThat(seen).containsExactly(false, 1, 0, true);
    }

    @Test
    @DisplayName("tier 1: a failed delivery fails the request; the committed pending row stays, and no code is left")
    void tier1FailedDeliveryKeepsThePendingRow() {
        String key = phone();
        doThrow(new NotificationDeliveryException("SMS gateway down")).when(sms).sendSms(anyString(), anyString(), anyString());
        doThrow(new NotificationDeliveryException("WhatsApp down")).when(whatsApp).sendCustomNotification(anyString(), anyString());
        CustomerTier1RegisterDTO tier1 = new CustomerTier1RegisterDTO();
        tier1.setPhoneNumber(key);
        tier1.setPassword("S3cur3Pass!");

        assertThatThrownBy(() -> customerService.registerTier1(tier1)).isInstanceOf(NotificationDeliveryException.class);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM pending_registrations WHERE phone_number = ?",
                Integer.class, key)).isEqualTo(1);
        assertThat(otpRow(key)).isNull();
        assertThat(attemptCount(key)).isNull();
    }
}
