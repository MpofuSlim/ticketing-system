package com.innbucks.userservice.service;

import com.innbucks.userservice.client.EmailNotificationClient;
import com.innbucks.userservice.client.SmsNotificationClient;
import com.innbucks.userservice.client.WhatsAppNotificationClient;
import com.innbucks.userservice.entity.Otp;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.integration.LoyaltyServiceClient;
import com.innbucks.userservice.repository.CustomerProfileRepository;
import com.innbucks.userservice.repository.OtpRepository;
import com.innbucks.userservice.repository.OtpRetryAttemptRepository;
import com.innbucks.userservice.repository.PendingRegistrationRepository;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.security.OtpHasher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * S4: customer support's reset code is ISSUED inside the caller's transaction
 * (quota counted, the OTP row written) and EMAILED only after that transaction
 * commits — never while the support action still holds the account's row lock,
 * and never for an action whose seal then failed and rolled back.
 */
class SupportPasswordResetAfterCommitTest {

    private static final OtpHasher HASHER = new OtpHasher("test-otp-hmac-secret-unit-tests-0123456789");

    private OtpRepository otps;
    private EmailNotificationClient email;
    private OtpService otpService;
    private final List<Boolean> delivered = new ArrayList<>();

    @BeforeEach
    void setUp() {
        otps = mock(OtpRepository.class);
        OtpRetryAttemptRepository retries = mock(OtpRetryAttemptRepository.class);
        when(retries.findByPhoneNumber(anyString())).thenReturn(Optional.empty());
        email = mock(EmailNotificationClient.class);
        otpService = new OtpService(otps, HASHER, retries, mock(UserRepository.class),
                mock(CustomerProfileRepository.class), mock(PendingRegistrationRepository.class),
                mock(LoyaltyServiceClient.class), mock(WhatsAppNotificationClient.class),
                mock(SmsNotificationClient.class), email);
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    private static void commit() {
        for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) s.afterCommit();
    }

    private static void rollback() {
        for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
            s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        }
    }

    @Test
    @DisplayName("the OTP row is written now, the email only after commit, and the code in it is the one stored")
    void issuedNowSentAfterCommit() {
        otpService.sendPasswordResetOtpToEmailAfterCommit("tariro@example.com", delivered::add);

        ArgumentCaptor<Otp> row = ArgumentCaptor.forClass(Otp.class);
        verify(otps).save(row.capture());
        verifyNoInteractions(email);                     // nothing leaves inside the transaction

        commit();
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(email).sendEmail(eq("tariro@example.com"), eq("Your InnBucks password reset code"), body.capture(),
                anyString());
        String code = body.getValue().replaceAll(".*code is (\\d{6}).*", "$1");
        assertThat(row.getValue().getCode()).isEqualTo(HASHER.hash(code));
        assertThat(delivered).containsExactly(true);
    }

    @Test
    @DisplayName("a transaction that rolls back (the seal failed) sends nothing")
    void rollbackSendsNothing() {
        otpService.sendPasswordResetOtpToEmailAfterCommit("tariro@example.com", delivered::add);
        rollback();
        verify(email, never()).sendEmail(anyString(), anyString(), anyString(), anyString());
        assertThat(delivered).isEmpty();
    }

    @Test
    @DisplayName("a failed send is reported, never thrown into the (already committed) action")
    void failedSendIsReported() {
        doThrow(new RuntimeException("gateway 403")).when(email).sendEmail(anyString(), anyString(), anyString(),
                anyString());
        otpService.sendPasswordResetOtpToEmailAfterCommit("tariro@example.com", delivered::add);
        commit();
        assertThat(delivered).containsExactly(false);
    }

    @Test
    @DisplayName("PasswordResetService: the same gates as the public flow, and false where it would silently no-op")
    void passwordResetServiceGates() {
        OtpService otp = mock(OtpService.class);
        UserRepository users = mock(UserRepository.class);
        StaffEligibility eligibility = mock(StaffEligibility.class);
        PasswordResetService service = new PasswordResetService(otp, users, null, null, null, null, null, null,
                eligibility);
        when(users.findByEmail("tariro@example.com")).thenReturn(Optional.of(User.builder().id(1L)
                .email("tariro@example.com").active(true).approved(true).build()));
        when(users.findByEmail("off@example.com")).thenReturn(Optional.of(User.builder().id(2L)
                .email("off@example.com").active(false).approved(true).build()));
        when(users.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        assertThat(service.requestResetForSupport(" tariro@example.com ", delivered::add)).isTrue();
        verify(otp).sendPasswordResetOtpToEmailAfterCommit(eq("tariro@example.com"), any());
        assertThat(service.requestResetForSupport("off@example.com", delivered::add)).isFalse();
        assertThat(service.requestResetForSupport("nobody@example.com", delivered::add)).isFalse();
        verify(otp, never()).sendPasswordResetOtpToEmail(anyString());
    }
}
