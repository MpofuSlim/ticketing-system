package com.innbucks.userservice.service;

import com.innbucks.userservice.client.EmailNotificationClient;
import com.innbucks.userservice.client.NotificationDeliveryException;
import com.innbucks.userservice.client.SmsNotificationClient;
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
import com.innbucks.userservice.security.OtpHasher;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class OtpServiceTest {

    // Same hasher the service is built with below — so assertions can check that
    // the PERSISTED code equals the HMAC of the code that was actually delivered
    // (A02: otps.code stores the HMAC, not the raw 6-digit code).
    private static final OtpHasher HASHER = new OtpHasher("test-otp-hmac-secret-unit-tests-0123456789");

    /** Pull the 6-digit code out of a dispatched OTP message. */
    private static String extractCode(String message) {
        Matcher m = Pattern.compile("\\b(\\d{6})\\b").matcher(message);
        assertTrue(m.find(), "message should carry a 6-digit code, was: " + message);
        return m.group(1);
    }

    private OtpService newService(OtpRepository otpRepo,
                                  OtpRetryAttemptRepository retryRepo,
                                  UserRepository userRepo,
                                  CustomerProfileRepository profileRepo,
                                  PendingRegistrationRepository pendingRepo) {
        return newService(otpRepo, retryRepo, userRepo, profileRepo, pendingRepo,
                mock(WhatsAppNotificationClient.class), mock(SmsNotificationClient.class));
    }

    private OtpService newService(OtpRepository otpRepo,
                                  OtpRetryAttemptRepository retryRepo,
                                  UserRepository userRepo,
                                  CustomerProfileRepository profileRepo,
                                  PendingRegistrationRepository pendingRepo,
                                  WhatsAppNotificationClient whatsApp,
                                  SmsNotificationClient sms) {
        return newService(otpRepo, retryRepo, userRepo, profileRepo, pendingRepo,
                whatsApp, sms, mock(EmailNotificationClient.class));
    }

    private OtpService newService(OtpRepository otpRepo,
                                  OtpRetryAttemptRepository retryRepo,
                                  UserRepository userRepo,
                                  CustomerProfileRepository profileRepo,
                                  PendingRegistrationRepository pendingRepo,
                                  WhatsAppNotificationClient whatsApp,
                                  SmsNotificationClient sms,
                                  EmailNotificationClient email) {
        // LoyaltyServiceClient.promoteUserByPhone is fired post-OTP-verify but
        // the call is best-effort and never affects assertions in these tests,
        // so a noop mock is fine.
        return new OtpService(otpRepo, HASHER, retryRepo, userRepo, profileRepo, pendingRepo,
                mock(LoyaltyServiceClient.class), whatsApp, sms, email);
    }

    @Test
    void sendOtp_replacesExistingAndPersistsRandomSixDigitCode() {
        OtpRepository otpRepo = mock(OtpRepository.class);
        OtpRetryAttemptRepository retryRepo = mock(OtpRetryAttemptRepository.class);
        when(retryRepo.findByPhoneNumber(anyString())).thenReturn(Optional.empty());
        WhatsAppNotificationClient whatsApp = mock(WhatsAppNotificationClient.class);
        SmsNotificationClient sms = mock(SmsNotificationClient.class);

        newService(otpRepo, retryRepo, mock(UserRepository.class), mock(CustomerProfileRepository.class), mock(PendingRegistrationRepository.class), whatsApp, sms)
                .sendOtp("+263771234567");

        verify(otpRepo).deleteByPhoneNumber("+263771234567");
        ArgumentCaptor<Otp> saved = ArgumentCaptor.forClass(Otp.class);
        verify(otpRepo).save(saved.capture());
        assertEquals("+263771234567", saved.getValue().getPhoneNumber());

        // SMS is the primary channel: the delivered message carries the raw
        // 6-digit code, while the PERSISTED value is its HMAC (A02). The WhatsApp
        // fallback is NOT touched on success.
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(sms).sendSms(eq("+263771234567"), message.capture(), anyString());
        String rawCode = extractCode(message.getValue());
        assertEquals(HASHER.hash(rawCode), saved.getValue().getCode(),
                "persisted OTP code should be the HMAC of the delivered code");
        verifyNoInteractions(whatsApp);
    }

    @Test
    void sendOtp_fallsBackToWhatsAppWhenSmsFails() {
        OtpRepository otpRepo = mock(OtpRepository.class);
        OtpRetryAttemptRepository retryRepo = mock(OtpRetryAttemptRepository.class);
        when(retryRepo.findByPhoneNumber(anyString())).thenReturn(Optional.empty());
        WhatsAppNotificationClient whatsApp = mock(WhatsAppNotificationClient.class);
        SmsNotificationClient sms = mock(SmsNotificationClient.class);
        doThrow(new NotificationDeliveryException("SMS gateway down"))
                .when(sms).sendSms(anyString(), anyString(), anyString());

        newService(otpRepo, retryRepo, mock(UserRepository.class), mock(CustomerProfileRepository.class), mock(PendingRegistrationRepository.class), whatsApp, sms)
                .sendOtp("+263771234567");

        // SMS failed, so the OTP is delivered via the WhatsApp fallback, still
        // carrying the exact persisted code.
        ArgumentCaptor<Otp> saved = ArgumentCaptor.forClass(Otp.class);
        verify(otpRepo).save(saved.capture());
        ArgumentCaptor<String> waMsg = ArgumentCaptor.forClass(String.class);
        verify(whatsApp).sendCustomNotification(eq("+263771234567"), waMsg.capture());
        assertEquals(HASHER.hash(extractCode(waMsg.getValue())), saved.getValue().getCode(),
                "persisted OTP code should be the HMAC of the delivered code");
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

        when(otpRepo.consume(eq("+263771234567"), eq(HASHER.hash("000000")), any())).thenReturn(1);
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

        when(otpRepo.consume(eq("+263771234567"), eq(HASHER.hash("000000")), any())).thenReturn(1);
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

    @Test
    void sendOtp_foreignMsisdn_routesDirectlyToWhatsApp_skippingSmsGateway() {
        // The current SMS gateway is single-country: on the ZW deployment it
        // accepts a KE-prefix number with a 2xx and silently drops it. Detect
        // the foreign prefix and skip SMS entirely, so the customer actually
        // gets the OTP via WhatsApp (which is global).
        OtpRepository otpRepo = mock(OtpRepository.class);
        OtpRetryAttemptRepository retryRepo = mock(OtpRetryAttemptRepository.class);
        when(retryRepo.findByPhoneNumber(anyString())).thenReturn(Optional.empty());
        WhatsAppNotificationClient whatsApp = mock(WhatsAppNotificationClient.class);
        SmsNotificationClient sms = mock(SmsNotificationClient.class);

        // default deploymentCountry is ZW; +254 = Kenya prefix
        newService(otpRepo, retryRepo, mock(UserRepository.class), mock(CustomerProfileRepository.class), mock(PendingRegistrationRepository.class), whatsApp, sms)
                .sendOtp("+254712345678");

        ArgumentCaptor<Otp> saved = ArgumentCaptor.forClass(Otp.class);
        verify(otpRepo).save(saved.capture());
        ArgumentCaptor<String> waMsg = ArgumentCaptor.forClass(String.class);
        verify(whatsApp).sendCustomNotification(eq("+254712345678"), waMsg.capture());
        assertEquals(HASHER.hash(extractCode(waMsg.getValue())), saved.getValue().getCode(),
                "persisted OTP code should be the HMAC of the delivered code");
        // SMS gateway is NEVER hit for a foreign-prefix number.
        verifyNoInteractions(sms);
    }

    @Test
    void sendOtp_unresolvableMsisdn_treatedAsForeign_routedToWhatsApp() {
        // Defensive: if the prefix isn't in InnBucks' markets table, route to
        // WhatsApp (works on any E.164) rather than claim a successful SMS
        // that the local gateway will silently drop.
        OtpRepository otpRepo = mock(OtpRepository.class);
        OtpRetryAttemptRepository retryRepo = mock(OtpRetryAttemptRepository.class);
        when(retryRepo.findByPhoneNumber(anyString())).thenReturn(Optional.empty());
        WhatsAppNotificationClient whatsApp = mock(WhatsAppNotificationClient.class);
        SmsNotificationClient sms = mock(SmsNotificationClient.class);

        // +1 (US) is intentionally NOT in the InnBucks markets list.
        newService(otpRepo, retryRepo, mock(UserRepository.class), mock(CustomerProfileRepository.class), mock(PendingRegistrationRepository.class), whatsApp, sms)
                .sendOtp("+12025551234");

        verify(whatsApp).sendCustomNotification(eq("+12025551234"), anyString());
        verifyNoInteractions(sms);
    }

    @Test
    void sendOtp_keNumberOnKeDeployment_isDomestic_smsPath() {
        // Symmetric: a +254 number on a KE deployment IS domestic — SMS first.
        OtpRepository otpRepo = mock(OtpRepository.class);
        OtpRetryAttemptRepository retryRepo = mock(OtpRetryAttemptRepository.class);
        when(retryRepo.findByPhoneNumber(anyString())).thenReturn(Optional.empty());
        WhatsAppNotificationClient whatsApp = mock(WhatsAppNotificationClient.class);
        SmsNotificationClient sms = mock(SmsNotificationClient.class);

        OtpService service = newService(otpRepo, retryRepo, mock(UserRepository.class),
                mock(CustomerProfileRepository.class), mock(PendingRegistrationRepository.class),
                whatsApp, sms);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "deploymentCountry", "KE");

        service.sendOtp("+254712345678");

        verify(sms).sendSms(eq("+254712345678"), anyString(), anyString());
        verifyNoInteractions(whatsApp);
    }

    // ---- password-reset OTP -------------------------------------------------

    @Test
    void sendPasswordResetOtpToPhone_mintsAndSendsResetCodeViaSms() {
        OtpRepository otpRepo = mock(OtpRepository.class);
        OtpRetryAttemptRepository retryRepo = mock(OtpRetryAttemptRepository.class);
        WhatsAppNotificationClient whatsApp = mock(WhatsAppNotificationClient.class);
        SmsNotificationClient sms = mock(SmsNotificationClient.class);
        when(retryRepo.findByPhoneNumber(anyString())).thenReturn(Optional.empty());

        newService(otpRepo, retryRepo, mock(UserRepository.class), mock(CustomerProfileRepository.class),
                mock(PendingRegistrationRepository.class), whatsApp, sms)
                .sendPasswordResetOtpToPhone("+263771234567");

        verify(otpRepo).save(any(Otp.class));
        verify(sms).sendSms(eq("+263771234567"), contains("password reset code"), anyString());
    }

    @Test
    void sendPasswordResetOtpToEmail_mintsCodeKeyedByEmail_andSendsEmail() {
        OtpRepository otpRepo = mock(OtpRepository.class);
        OtpRetryAttemptRepository retryRepo = mock(OtpRetryAttemptRepository.class);
        WhatsAppNotificationClient whatsApp = mock(WhatsAppNotificationClient.class);
        SmsNotificationClient sms = mock(SmsNotificationClient.class);
        EmailNotificationClient email = mock(EmailNotificationClient.class);
        when(retryRepo.findByPhoneNumber(anyString())).thenReturn(Optional.empty());

        newService(otpRepo, retryRepo, mock(UserRepository.class), mock(CustomerProfileRepository.class),
                mock(PendingRegistrationRepository.class), whatsApp, sms, email)
                .sendPasswordResetOtpToEmail("user@example.com");

        // OTP row is keyed by the email (so verify uses the same identifier).
        ArgumentCaptor<Otp> saved = ArgumentCaptor.forClass(Otp.class);
        verify(otpRepo).save(saved.capture());
        assertEquals("user@example.com", saved.getValue().getPhoneNumber());
        verify(email).sendEmail(eq("user@example.com"), contains("password reset"),
                contains("password reset code"), anyString());
        verifyNoInteractions(sms, whatsApp);
    }

    @Test
    void verifyPasswordResetOtp_correct_consumes_withoutCustomerFinalisation() {
        OtpRepository otpRepo = mock(OtpRepository.class);
        OtpRetryAttemptRepository retryRepo = mock(OtpRetryAttemptRepository.class);
        PendingRegistrationRepository pendingRepo = mock(PendingRegistrationRepository.class);
        when(otpRepo.consume(eq("+263771234567"), eq(HASHER.hash("123456")), any())).thenReturn(1);
        when(retryRepo.findByPhoneNumber(anyString())).thenReturn(Optional.empty());

        boolean ok = newService(otpRepo, retryRepo, mock(UserRepository.class),
                mock(CustomerProfileRepository.class), pendingRepo)
                .verifyPasswordResetOtp("+263771234567", "123456");

        assertTrue(ok);
        // The registration-only side effect must NOT run for a password reset.
        verify(pendingRepo, never()).findByPhoneNumber(anyString());
    }

    @Test
    void verifyPasswordResetOtp_wrong_returnsFalse_andBumpsFailedAttempts() {
        OtpRepository otpRepo = mock(OtpRepository.class);
        OtpRetryAttemptRepository retryRepo = mock(OtpRetryAttemptRepository.class);
        when(otpRepo.consume(anyString(), anyString(), any())).thenReturn(0);
        Otp live = Otp.builder().phoneNumber("+263771234567").code("999999").failedAttempts(0).build();
        when(otpRepo.findByPhoneNumber("+263771234567")).thenReturn(Optional.of(live));

        boolean ok = newService(otpRepo, retryRepo, mock(UserRepository.class),
                mock(CustomerProfileRepository.class), mock(PendingRegistrationRepository.class))
                .verifyPasswordResetOtp("+263771234567", "000000");

        assertFalse(ok);
        ArgumentCaptor<Otp> saved = ArgumentCaptor.forClass(Otp.class);
        verify(otpRepo).save(saved.capture());
        assertEquals(1, saved.getValue().getFailedAttempts());
    }
}
