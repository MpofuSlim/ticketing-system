package com.innbucks.userservice.service;

import com.innbucks.userservice.client.WhatsAppNotificationClient;
import com.innbucks.userservice.entity.CustomerProfile;
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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class OtpServiceTest {

    private OtpService newService(OtpRepository otpRepo,
                                  OtpRetryAttemptRepository retryRepo,
                                  UserRepository userRepo,
                                  CustomerProfileRepository profileRepo,
                                  PendingRegistrationRepository pendingRepo) {
        return newService(otpRepo, retryRepo, userRepo, profileRepo, pendingRepo,
                mock(WhatsAppNotificationClient.class));
    }

    private OtpService newService(OtpRepository otpRepo,
                                  OtpRetryAttemptRepository retryRepo,
                                  UserRepository userRepo,
                                  CustomerProfileRepository profileRepo,
                                  PendingRegistrationRepository pendingRepo,
                                  WhatsAppNotificationClient whatsApp) {
        // LoyaltyServiceClient.promoteUserByPhone is fired post-OTP-verify but
        // the call is best-effort and never affects assertions in these tests,
        // so a noop mock is fine.
        return new OtpService(otpRepo, retryRepo, userRepo, profileRepo, pendingRepo,
                mock(LoyaltyServiceClient.class), whatsApp);
    }

    @Test
    void sendOtp_replacesExistingAndPersistsRandomSixDigitCode() {
        OtpRepository otpRepo = mock(OtpRepository.class);
        OtpRetryAttemptRepository retryRepo = mock(OtpRetryAttemptRepository.class);
        when(retryRepo.findByPhoneNumber(anyString())).thenReturn(Optional.empty());
        WhatsAppNotificationClient whatsApp = mock(WhatsAppNotificationClient.class);

        newService(otpRepo, retryRepo, mock(UserRepository.class), mock(CustomerProfileRepository.class), mock(PendingRegistrationRepository.class), whatsApp)
                .sendOtp("+263771234567");

        verify(otpRepo).deleteByPhoneNumber("+263771234567");
        ArgumentCaptor<Otp> saved = ArgumentCaptor.forClass(Otp.class);
        verify(otpRepo).save(saved.capture());
        String code = saved.getValue().getCode();
        assertTrue(code.matches("\\d{6}"), "OTP should be a 6-digit code, was: " + code);
        assertEquals("+263771234567", saved.getValue().getPhoneNumber());

        // The exact code that was persisted is delivered to the same phone.
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(whatsApp).sendCustomNotification(eq("+263771234567"), message.capture());
        assertTrue(message.getValue().contains(code),
                "WhatsApp message should carry the generated OTP code");
    }

    @Test
    void sendOtp_triggersLockoutOnFourthRequestWithinWindow() {
        OtpRepository otpRepo = mock(OtpRepository.class);
        OtpRetryAttemptRepository retryRepo = mock(OtpRetryAttemptRepository.class);

        OtpRetryAttempt attempt = OtpRetryAttempt.builder()
                .phoneNumber("+263771234567")
                .attemptCount(3)
                .windowStartsAt(Instant.now().minusSeconds(60))
                .build();
        when(retryRepo.findByPhoneNumber("+263771234567")).thenReturn(Optional.of(attempt));

        OtpService service = newService(otpRepo, retryRepo, mock(UserRepository.class), mock(CustomerProfileRepository.class), mock(PendingRegistrationRepository.class));

        OtpService.OtpRateLimitException ex = assertThrows(OtpService.OtpRateLimitException.class,
                () -> service.sendOtp("+263771234567"));
        assertTrue(ex.getMessage().contains("30"));
        assertNotNull(attempt.getLockedUntil());
        verify(otpRepo, never()).save(any());
    }

    @Test
    void sendOtp_rejectsImmediatelyWhenStillLockedOut() {
        OtpRepository otpRepo = mock(OtpRepository.class);
        OtpRetryAttemptRepository retryRepo = mock(OtpRetryAttemptRepository.class);

        OtpRetryAttempt attempt = OtpRetryAttempt.builder()
                .phoneNumber("+263771234567")
                .attemptCount(4)
                .windowStartsAt(Instant.now().minusSeconds(120))
                .lockedUntil(Instant.now().plusSeconds(600))
                .build();
        when(retryRepo.findByPhoneNumber("+263771234567")).thenReturn(Optional.of(attempt));

        OtpService service = newService(otpRepo, retryRepo, mock(UserRepository.class), mock(CustomerProfileRepository.class), mock(PendingRegistrationRepository.class));

        assertThrows(OtpService.OtpRateLimitException.class, () -> service.sendOtp("+263771234567"));
        verify(otpRepo, never()).save(any());
    }

    @Test
    void sendOtp_windowResetsAfterTenMinutes() {
        OtpRepository otpRepo = mock(OtpRepository.class);
        OtpRetryAttemptRepository retryRepo = mock(OtpRetryAttemptRepository.class);

        OtpRetryAttempt attempt = OtpRetryAttempt.builder()
                .phoneNumber("+263771234567")
                .attemptCount(3)
                .windowStartsAt(Instant.now().minusSeconds(15 * 60))
                .build();
        when(retryRepo.findByPhoneNumber("+263771234567")).thenReturn(Optional.of(attempt));

        newService(otpRepo, retryRepo, mock(UserRepository.class), mock(CustomerProfileRepository.class), mock(PendingRegistrationRepository.class))
                .sendOtp("+263771234567");

        // Window rolled over: counter reset to 1, OTP sent successfully
        assertEquals(1, attempt.getAttemptCount());
        verify(otpRepo).save(any(Otp.class));
    }

    @Test
    void verifyOtp_onCorrectCode_withPendingRegistration_materialisesAccount() {
        OtpRepository otpRepo = mock(OtpRepository.class);
        OtpRetryAttemptRepository retryRepo = mock(OtpRetryAttemptRepository.class);
        UserRepository userRepo = mock(UserRepository.class);
        CustomerProfileRepository profileRepo = mock(CustomerProfileRepository.class);
        PendingRegistrationRepository pendingRepo = mock(PendingRegistrationRepository.class);

        when(otpRepo.consume(eq("+263771234567"), eq("000000"), any())).thenReturn(1);
        PendingRegistration pending = PendingRegistration.builder()
                .phoneNumber("+263771234567")
                .passwordHash("hashed-pw")
                .build();
        when(pendingRepo.findByPhoneNumber("+263771234567")).thenReturn(Optional.of(pending));

        boolean ok = newService(otpRepo, retryRepo, userRepo, profileRepo, pendingRepo)
                .verifyOtp("+263771234567", "000000");

        assertTrue(ok);
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepo).save(userCaptor.capture());
        assertEquals("+263771234567", userCaptor.getValue().getPhoneNumber());
        assertEquals("hashed-pw", userCaptor.getValue().getPassword());
        assertTrue(userCaptor.getValue().getRoles().contains(User.Role.CUSTOMER));

        ArgumentCaptor<CustomerProfile> profileCaptor = ArgumentCaptor.forClass(CustomerProfile.class);
        verify(profileRepo).save(profileCaptor.capture());
        assertTrue(profileCaptor.getValue().isPhoneVerified());
        assertEquals(1, profileCaptor.getValue().getRegistrationTier());

        verify(pendingRepo).delete(pending);
    }

    @Test
    void verifyOtp_onCorrectCode_withoutPending_butExistingUser_flipsPhoneVerified() {
        OtpRepository otpRepo = mock(OtpRepository.class);
        OtpRetryAttemptRepository retryRepo = mock(OtpRetryAttemptRepository.class);
        UserRepository userRepo = mock(UserRepository.class);
        CustomerProfileRepository profileRepo = mock(CustomerProfileRepository.class);
        PendingRegistrationRepository pendingRepo = mock(PendingRegistrationRepository.class);

        when(otpRepo.consume(eq("+263771234567"), eq("000000"), any())).thenReturn(1);
        when(pendingRepo.findByPhoneNumber("+263771234567")).thenReturn(Optional.empty());
        User user = User.builder().id(7L).phoneNumber("+263771234567")
                .roles(java.util.EnumSet.of(User.Role.CUSTOMER)).build();
        CustomerProfile profile = CustomerProfile.builder().user(user).registrationTier(1).phoneVerified(false).build();
        when(userRepo.findByPhoneNumber("+263771234567")).thenReturn(Optional.of(user));
        when(profileRepo.findByUserId(7L)).thenReturn(Optional.of(profile));

        boolean ok = newService(otpRepo, retryRepo, userRepo, profileRepo, pendingRepo)
                .verifyOtp("+263771234567", "000000");

        assertTrue(ok);
        assertTrue(profile.isPhoneVerified());
        verify(profileRepo).save(profile);
        verify(userRepo, never()).save(any()); // no account creation in this branch
    }

    @Test
    void verifyOtp_onWrongCode_incrementsFailedAttempts() {
        OtpRepository otpRepo = mock(OtpRepository.class);
        when(otpRepo.consume(anyString(), anyString(), any())).thenReturn(0);
        Otp otp = Otp.builder().phoneNumber("+263771234567").code("000000").failedAttempts(0).build();
        when(otpRepo.findByPhoneNumber("+263771234567")).thenReturn(Optional.of(otp));

        boolean ok = newService(otpRepo, mock(OtpRetryAttemptRepository.class), mock(UserRepository.class), mock(CustomerProfileRepository.class), mock(PendingRegistrationRepository.class))
                .verifyOtp("+263771234567", "999999");

        assertFalse(ok);
        assertEquals(1, otp.getFailedAttempts());
        verify(otpRepo).save(otp);
    }

    @Test
    void verifyOtp_afterThreeFailures_invalidatesOtp() {
        OtpRepository otpRepo = mock(OtpRepository.class);
        when(otpRepo.consume(anyString(), anyString(), any())).thenReturn(0);
        Otp otp = Otp.builder().phoneNumber("+263771234567").code("000000").failedAttempts(2).build();
        when(otpRepo.findByPhoneNumber("+263771234567")).thenReturn(Optional.of(otp));

        newService(otpRepo, mock(OtpRetryAttemptRepository.class), mock(UserRepository.class), mock(CustomerProfileRepository.class), mock(PendingRegistrationRepository.class))
                .verifyOtp("+263771234567", "999999");

        verify(otpRepo).delete(otp);
        verify(otpRepo, never()).save(any());
    }
}
