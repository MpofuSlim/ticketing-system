package com.innbucks.userservice.service;

import com.innbucks.userservice.client.NotificationDeliveryException;
import com.innbucks.userservice.dto.CustomerTier1RegisterDTO;
import com.innbucks.userservice.entity.PendingRegistration;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.CustomerProfileRepository;
import com.innbucks.userservice.repository.DeviceRepository;
import com.innbucks.userservice.repository.PendingRegistrationRepository;
import com.innbucks.userservice.repository.RefreshTokenRepository;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.security.NationalIdHasher;
import com.innbucks.userservice.testsupport.RecordingTransactionManager;
import com.innbucks.userservice.testsupport.RecordingTransactionManager.Outcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.innbucks.userservice.testsupport.RecordingTransactionManager.inTransaction;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The two callers that used to wrap an OTP send in their OWN transaction —
 * tier-1 registration and the forgot-password request — now commit their work
 * first and send after, so the SMS / WhatsApp / email round trip never runs
 * inside their transaction either.
 */
class OtpCallersSendOutsideTransactionTest {

    private static final String PHONE = "+263771234567";

    // ---- CustomerService.registerTier1 --------------------------------------------

    private record Tier1(CustomerService service, OtpService otp, PendingRegistrationRepository pending,
                         PasswordEncoder encoder, RecordingTransactionManager tm) {}

    private static Tier1 tier1() {
        UserRepository users = mock(UserRepository.class);
        PendingRegistrationRepository pending = mock(PendingRegistrationRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        OtpService otp = mock(OtpService.class);
        CustomerService service = new CustomerService(users, mock(CustomerProfileRepository.class),
                mock(DeviceRepository.class), pending, encoder, otp, new NationalIdHasher("test-secret"),
                mock(StaffEligibility.class));
        RecordingTransactionManager tm = new RecordingTransactionManager();
        service.setTransactionManager(tm);
        return new Tier1(service, otp, pending, encoder, tm);
    }

    private static CustomerTier1RegisterDTO request() {
        CustomerTier1RegisterDTO dto = new CustomerTier1RegisterDTO();
        dto.setPhoneNumber("0771234567");
        dto.setPassword("S3cur3Pass!");
        return dto;
    }

    @Test
    @DisplayName("tier 1: the pending row commits in a transaction, the password is hashed before it, the OTP is sent after it")
    void tier1_pendingRowCommitsBeforeTheSend() {
        Tier1 t = tier1();
        List<Boolean> hashedInTransaction = new ArrayList<>();
        when(t.encoder().encode(anyString())).thenAnswer(inv -> {
            hashedInTransaction.add(inTransaction());
            return "$argon2id$hash";
        });
        List<Boolean> savedInTransaction = new ArrayList<>();
        when(t.pending().save(any(PendingRegistration.class))).thenAnswer(inv -> {
            savedInTransaction.add(inTransaction());
            return inv.getArgument(0);
        });
        List<Boolean> sentInTransaction = new ArrayList<>();
        doAnswer(inv -> {
            sentInTransaction.add(inTransaction());
            assertThat(t.tm().outcomes()).containsExactly(Outcome.COMMITTED);
            return null;
        }).when(t.otp()).sendOtp(PHONE);

        var response = t.service().registerTier1(request());

        assertThat(response.getPhoneNumber()).isEqualTo(PHONE);
        assertThat(hashedInTransaction).containsExactly(false);
        assertThat(savedInTransaction).containsExactly(true);
        assertThat(sentInTransaction).containsExactly(false);
        verify(t.pending()).deleteByPhoneNumber(PHONE);
    }

    @Test
    @DisplayName("tier 1: a failed delivery still fails the request, and the committed pending row stays (a retry replaces it)")
    void tier1_failedDeliveryKeepsThePendingRow() {
        Tier1 t = tier1();
        when(t.encoder().encode(anyString())).thenReturn("$argon2id$hash");
        NotificationDeliveryException down = new NotificationDeliveryException("down");
        doThrow(down).when(t.otp()).sendOtp(PHONE);

        assertThatThrownBy(() -> t.service().registerTier1(request())).isSameAs(down);

        verify(t.pending()).save(any(PendingRegistration.class));
        verify(t.pending(), never()).delete(any(PendingRegistration.class));
        assertThat(t.tm().outcomes()).containsExactly(Outcome.COMMITTED);
    }

    // ---- PasswordResetService.requestReset ------------------------------------------

    private record Reset(PasswordResetService service, OtpService otp, UserRepository users,
                         RecordingTransactionManager tm) {}

    private static Reset reset() {
        OtpService otp = mock(OtpService.class);
        UserRepository users = mock(UserRepository.class);
        com.innbucks.userservice.repository.RoleRepository roles =
                mock(com.innbucks.userservice.repository.RoleRepository.class);
        com.innbucks.userservice.testsupport.BuiltInRoleRows.stub(roles);
        PasswordResetService service = new PasswordResetService(otp, users, mock(PasswordEncoder.class),
                mock(RefreshTokenRepository.class), mock(AuditService.class),
                mock(org.springframework.context.ApplicationEventPublisher.class), null,
                new RoleGrantGuard(users, roles), mock(StaffEligibility.class));
        RecordingTransactionManager tm = new RecordingTransactionManager();
        service.setTransactionManager(tm);
        return new Reset(service, otp, users, tm);
    }

    @Test
    @DisplayName("forgot password: the gates are read in a read-only transaction; the code is sent after it, with none open")
    void requestReset_sendsAfterTheGates() {
        Reset r = reset();
        when(r.users().findByPhoneNumber(PHONE)).thenAnswer(inv -> {
            assertThat(inTransaction()).isTrue();
            return Optional.of(User.builder().id(3L).phoneNumber(PHONE)
                    .roles(User.roleNames(User.Role.CUSTOMER)).active(true).approved(true).build());
        });
        List<Boolean> sentInTransaction = new ArrayList<>();
        doAnswer(inv -> sentInTransaction.add(inTransaction())).when(r.otp()).sendPasswordResetOtpToPhone(PHONE);

        r.service().requestReset("0771234567", null);

        assertThat(sentInTransaction).containsExactly(false);
        assertThat(r.tm().outcomes()).containsExactly(Outcome.COMMITTED);
        assertThat(r.tm().readOnlyFlags()).containsExactly(true);
    }

    @Test
    @DisplayName("forgot password by email: same order — gates, then the send outside the transaction")
    void requestReset_byEmail_sendsAfterTheGates() {
        Reset r = reset();
        when(r.users().findByEmail("tariro@example.com")).thenReturn(Optional.of(User.builder().id(4L)
                .email("tariro@example.com").active(true).approved(true).build()));
        List<Boolean> sentInTransaction = new ArrayList<>();
        doAnswer(inv -> sentInTransaction.add(inTransaction()))
                .when(r.otp()).sendPasswordResetOtpToEmail("tariro@example.com");

        r.service().requestReset(null, "tariro@example.com");

        assertThat(sentInTransaction).containsExactly(false);
    }

    @Test
    @DisplayName("forgot password: a refused gate (deactivated account) is still the silent no-op, and nothing is sent")
    void requestReset_refusedGate_isStillSilent() {
        Reset r = reset();
        when(r.users().findByPhoneNumber(PHONE)).thenReturn(Optional.of(User.builder().id(5L).phoneNumber(PHONE)
                .roles(User.roleNames(User.Role.CUSTOMER)).active(false).approved(true).build()));

        r.service().requestReset(PHONE, null);

        verify(r.otp(), never()).sendPasswordResetOtpToPhone(anyString());
        verify(r.otp(), never()).sendPasswordResetOtpToEmail(anyString());
    }
}
