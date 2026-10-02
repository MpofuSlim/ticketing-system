package com.innbucks.userservice.service;

import com.innbucks.userservice.client.EmailNotificationClient;
import com.innbucks.userservice.client.NotificationDeliveryException;
import com.innbucks.userservice.client.SmsNotificationClient;
import com.innbucks.userservice.client.WhatsAppNotificationClient;
import com.innbucks.userservice.entity.Otp;
import com.innbucks.userservice.entity.OtpRetryAttempt;
import com.innbucks.userservice.entity.PendingRegistration;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.integration.LoyaltyServiceClient;
import com.innbucks.userservice.repository.CustomerProfileRepository;
import com.innbucks.userservice.repository.OtpRepository;
import com.innbucks.userservice.repository.OtpRetryAttemptRepository;
import com.innbucks.userservice.repository.PendingRegistrationRepository;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.security.OtpHasher;
import com.innbucks.userservice.testsupport.RecordingTransactionManager;
import com.innbucks.userservice.testsupport.RecordingTransactionManager.Outcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.innbucks.userservice.testsupport.RecordingTransactionManager.inTransaction;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Where OtpService's network calls run relative to its transactions.
 *
 * <p>The send used to be one {@code @Transactional} method, so the
 * {@code otps} and {@code otp_retry_attempts} rows stayed locked (and a pooled
 * connection held) for the whole SMS attempt and its WhatsApp fallback, and the
 * verify held the consumed OTP and the new account's rows across the loyalty
 * webhook. Each answer below asserts the call it stands for ran with NO
 * transaction open; the repository answers assert the writes still ran inside
 * one, so a test that passed because nothing was transactional would fail.
 */
class OtpSendOutsideTransactionTest {

    private static final String PHONE = "+263771234567";
    private static final OtpHasher HASHER = new OtpHasher("test-otp-hmac-secret-unit-tests-0123456789");

    private OtpRepository otps;
    private OtpRetryAttemptRepository retries;
    private UserRepository users;
    private PendingRegistrationRepository pending;
    private LoyaltyServiceClient loyalty;
    private SmsNotificationClient sms;
    private WhatsAppNotificationClient whatsApp;
    private EmailNotificationClient email;
    private RecordingTransactionManager tm;
    private OtpService service;
    /** Each repository write, and whether it ran inside a transaction. */
    private final List<Boolean> writesInTransaction = new ArrayList<>();
    /** Every Otp row saved, in order. */
    private final List<Otp> savedOtps = new ArrayList<>();

    @BeforeEach
    void setUp() {
        otps = mock(OtpRepository.class);
        retries = mock(OtpRetryAttemptRepository.class);
        users = mock(UserRepository.class);
        pending = mock(PendingRegistrationRepository.class);
        loyalty = mock(LoyaltyServiceClient.class);
        sms = mock(SmsNotificationClient.class);
        whatsApp = mock(WhatsAppNotificationClient.class);
        email = mock(EmailNotificationClient.class);
        when(retries.findByPhoneNumber(anyString())).thenReturn(Optional.empty());
        when(otps.save(any(Otp.class))).thenAnswer(inv -> {
            writesInTransaction.add(inTransaction());
            savedOtps.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(retries.save(any(OtpRetryAttempt.class))).thenAnswer(inv -> {
            writesInTransaction.add(inTransaction());
            return inv.getArgument(0);
        });
        service = new OtpService(otps, HASHER, retries, users, mock(CustomerProfileRepository.class), pending,
                loyalty, whatsApp, sms, email);
        tm = new RecordingTransactionManager();
        service.setTransactionManager(tm);
    }

    // ---- send ---------------------------------------------------------------------

    @Test
    @DisplayName("the code and the quota count commit first; the SMS is sent with no transaction open")
    void smsRunsOutsideTheTransaction() {
        List<Boolean> smsInTransaction = new ArrayList<>();
        doAnswer(inv -> smsInTransaction.add(inTransaction())).when(sms).sendSms(anyString(), anyString(), anyString());

        service.sendOtp(PHONE);

        assertThat(smsInTransaction).containsExactly(false);
        assertThat(writesInTransaction).isNotEmpty().containsOnly(true);
        assertThat(tm.outcomes()).containsExactly(Outcome.COMMITTED);
    }

    @Test
    @DisplayName("the WhatsApp fallback after a failed SMS also runs outside any transaction, and nothing is put back")
    void whatsAppFallbackRunsOutsideTheTransaction() {
        smsFails();
        List<Boolean> whatsAppInTransaction = new ArrayList<>();
        doAnswer(inv -> whatsAppInTransaction.add(inTransaction()))
                .when(whatsApp).sendCustomNotification(anyString(), anyString());

        service.sendOtp(PHONE);

        assertThat(whatsAppInTransaction).containsExactly(false);
        assertThat(tm.outcomes()).containsExactly(Outcome.COMMITTED);   // one phase, no compensation
        verify(otps, never()).delete(any(Otp.class));
    }

    @Test
    @DisplayName("password-reset by phone and by email: the message leaves after the commit")
    void resetSendsRunOutsideTheTransaction() {
        List<Boolean> sent = new ArrayList<>();
        doAnswer(inv -> sent.add(inTransaction())).when(sms).sendSms(anyString(), anyString(), anyString());
        doAnswer(inv -> sent.add(inTransaction())).when(email).sendEmail(anyString(), anyString(), anyString(), anyString());

        service.sendPasswordResetOtpToPhone(PHONE);
        service.sendPasswordResetOtpToEmail("tariro@example.com");

        assertThat(sent).containsExactly(false, false);
        assertThat(tm.outcomes()).containsExactly(Outcome.COMMITTED, Outcome.COMMITTED);
    }

    @Test
    @DisplayName("over the quota: refused inside the first phase, which rolls back, and nothing is sent")
    void rateLimitedIsRefusedBeforeAnySend() {
        when(retries.findByPhoneNumber(PHONE)).thenReturn(Optional.of(OtpRetryAttempt.builder()
                .phoneNumber(PHONE).attemptCount(OtpService.RETRY_LIMIT).windowStartsAt(Instant.now()).build()));

        assertThatThrownBy(() -> service.sendOtp(PHONE)).isInstanceOf(OtpService.OtpRateLimitException.class);

        verifyNoInteractions(sms, whatsApp);
        assertThat(tm.outcomes()).containsExactly(Outcome.ROLLED_BACK);
    }

    // ---- a failed delivery puts the previous state back -----------------------------

    @Test
    @DisplayName("delivery fails: the previous code (with its failed attempts) and the counter are put back, then the failure is rethrown")
    void failedDeliveryRestoresThePreviousState() {
        Instant earlier = Instant.now().minusSeconds(60);
        Otp previous = Otp.builder().id(1L).phoneNumber(PHONE).code(HASHER.hash("111111"))
                .expiresAt(earlier.plus(OtpService.OTP_TTL)).createdAt(earlier).failedAttempts(1).build();
        OtpRetryAttempt counter = OtpRetryAttempt.builder().id(5L).phoneNumber(PHONE)
                .attemptCount(1).windowStartsAt(earlier).build();
        when(retries.findByPhoneNumber(PHONE)).thenReturn(Optional.of(counter));
        // Before the send the previous row; at revert time the row this send wrote.
        when(otps.findByPhoneNumber(PHONE))
                .thenReturn(Optional.of(previous))
                .thenAnswer(inv -> Optional.of(savedOtps.get(savedOtps.size() - 1)));
        smsFails();
        NotificationDeliveryException whatsAppDown = new NotificationDeliveryException("WhatsApp down");
        org.mockito.Mockito.doThrow(whatsAppDown).when(whatsApp).sendCustomNotification(anyString(), anyString());

        assertThatThrownBy(() -> service.sendOtp(PHONE)).isSameAs(whatsAppDown);

        assertThat(savedOtps).hasSize(2);
        Otp issued = savedOtps.get(0);
        Otp restored = savedOtps.get(1);
        assertThat(issued.getCode()).isNotEqualTo(previous.getCode());
        verify(otps).delete(issued);
        assertThat(restored.getCode()).isEqualTo(HASHER.hash("111111"));
        assertThat(restored.getFailedAttempts()).isEqualTo(1);
        assertThat(restored.getExpiresAt()).isEqualTo(previous.getExpiresAt());
        assertThat(restored.getCreatedAt()).isEqualTo(previous.getCreatedAt());
        assertThat(restored.getPhoneNumber()).isEqualTo(PHONE);
        // The counter is the same managed row the quota check bumped to 2; it is back at 1.
        assertThat(counter.getAttemptCount()).isEqualTo(1);
        assertThat(counter.getWindowStartsAt()).isEqualTo(earlier);
        assertThat(tm.outcomes()).containsExactly(Outcome.COMMITTED, Outcome.COMMITTED);
        assertThat(writesInTransaction).containsOnly(true);
    }

    @Test
    @DisplayName("a first-ever send that fails leaves no code and no counter behind")
    void failedFirstSendLeavesNothing() {
        OtpRetryAttempt[] created = new OtpRetryAttempt[1];
        when(retries.save(any(OtpRetryAttempt.class))).thenAnswer(inv -> created[0] = inv.getArgument(0));
        String address = "tariro@example.com";
        when(retries.findByPhoneNumber(address))
                .thenReturn(Optional.empty())               // the snapshot
                .thenReturn(Optional.empty())               // the quota check
                .thenAnswer(inv -> Optional.ofNullable(created[0]));   // the revert
        when(otps.findByPhoneNumber(address))
                .thenReturn(Optional.empty())
                .thenAnswer(inv -> Optional.of(savedOtps.get(savedOtps.size() - 1)));
        org.mockito.Mockito.doThrow(new NotificationDeliveryException("down"))
                .when(email).sendEmail(anyString(), anyString(), anyString(), anyString());

        assertThatThrownBy(() -> service.sendPasswordResetOtpToEmail(address))
                .isInstanceOf(NotificationDeliveryException.class);

        assertThat(savedOtps).hasSize(1);               // only the issued one — nothing restored
        verify(otps).delete(savedOtps.get(0));
        assertThat(created[0]).isNotNull();
        verify(retries).delete(created[0]);
    }

    @Test
    @DisplayName("a newer code issued since is left alone — the revert only undoes what THIS send wrote")
    void aNewerCodeIsNotClobbered() {
        Otp newer = Otp.builder().id(9L).phoneNumber(PHONE).code(HASHER.hash("999999"))
                .expiresAt(Instant.now().plus(OtpService.OTP_TTL)).createdAt(Instant.now()).build();
        when(otps.findByPhoneNumber(PHONE)).thenReturn(Optional.empty()).thenReturn(Optional.of(newer));
        OtpRetryAttempt movedOn = OtpRetryAttempt.builder().id(5L).phoneNumber(PHONE)
                .attemptCount(2).windowStartsAt(Instant.now()).build();
        when(retries.findByPhoneNumber(PHONE))
                .thenReturn(Optional.empty()).thenReturn(Optional.empty()).thenReturn(Optional.of(movedOn));
        smsFails();
        org.mockito.Mockito.doThrow(new NotificationDeliveryException("down"))
                .when(whatsApp).sendCustomNotification(anyString(), anyString());

        assertThatThrownBy(() -> service.sendOtp(PHONE)).isInstanceOf(NotificationDeliveryException.class);

        verify(otps, never()).delete(any(Otp.class));
        verify(retries, never()).delete(any(OtpRetryAttempt.class));
        assertThat(movedOn.getAttemptCount()).isEqualTo(2);
    }

    // ---- verify ---------------------------------------------------------------------

    @Test
    @DisplayName("verify: the consume and the account work commit first; loyalty is told after, with no transaction open")
    void loyaltyIsToldAfterTheCommit() {
        when(otps.consume(eq(PHONE), eq(HASHER.hash("123456")), any())).thenAnswer(inv -> {
            assertThat(inTransaction()).isTrue();
            return 1;
        });
        when(pending.findByPhoneNumber(PHONE)).thenReturn(Optional.of(
                PendingRegistration.builder().phoneNumber(PHONE).passwordHash("h").build()));
        when(users.save(any(User.class))).thenAnswer(inv -> {
            writesInTransaction.add(inTransaction());
            return inv.getArgument(0);
        });
        List<Boolean> promotedInTransaction = new ArrayList<>();
        when(loyalty.promoteUserByPhone(PHONE)).thenAnswer(inv -> {
            promotedInTransaction.add(inTransaction());
            // ...and by then the verification has committed.
            assertThat(tm.outcomes()).containsExactly(Outcome.COMMITTED);
            return true;
        });

        assertThat(service.verifyOtp("0771234567", "123456")).isTrue();

        assertThat(promotedInTransaction).containsExactly(false);
        assertThat(writesInTransaction).containsExactly(true);
    }

    @Test
    @DisplayName("verify: a database phase that fails rolls back and promotes nobody")
    void failedVerifyTransactionPromotesNobody() {
        when(otps.consume(eq(PHONE), any(), any())).thenReturn(1);
        when(pending.findByPhoneNumber(PHONE)).thenReturn(Optional.of(
                PendingRegistration.builder().phoneNumber(PHONE).passwordHash("h").build()));
        when(users.save(any(User.class))).thenThrow(new org.springframework.dao.DataIntegrityViolationException("uk"));

        assertThatThrownBy(() -> service.verifyOtp(PHONE, "123456"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        verify(loyalty, never()).promoteUserByPhone(anyString());
        assertThat(tm.outcomes()).containsExactly(Outcome.ROLLED_BACK);
    }

    @Test
    @DisplayName("verify: a wrong code commits the spent attempt and promotes nobody")
    void wrongCodeCommitsTheAttemptAndPromotesNobody() {
        when(otps.consume(eq(PHONE), any(), any())).thenReturn(0);
        when(otps.findByPhoneNumber(PHONE)).thenReturn(Optional.of(Otp.builder().phoneNumber(PHONE)
                .code(HASHER.hash("111111")).expiresAt(Instant.now().plusSeconds(60)).createdAt(Instant.now()).build()));

        assertThat(service.verifyOtp(PHONE, "222222")).isFalse();

        verify(loyalty, never()).promoteUserByPhone(anyString());
        assertThat(tm.outcomes()).containsExactly(Outcome.COMMITTED);
        assertThat(writesInTransaction).containsExactly(true);
    }

    private void smsFails() {
        org.mockito.Mockito.doThrow(new NotificationDeliveryException("SMS gateway down"))
                .when(sms).sendSms(anyString(), anyString(), anyString());
    }
}
