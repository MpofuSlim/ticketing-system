package com.innbucks.userservice.service;

import com.innbucks.userservice.client.EmailNotificationClient;
import com.innbucks.userservice.client.SmsNotificationClient;
import com.innbucks.userservice.client.WhatsAppNotificationClient;
import com.innbucks.userservice.entity.CustomerProfile;
import com.innbucks.userservice.entity.PendingRegistration;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.integration.LoyaltyServiceClient;
import com.innbucks.userservice.repository.CustomerProfileRepository;
import com.innbucks.userservice.repository.OtpRepository;
import com.innbucks.userservice.repository.OtpRetryAttemptRepository;
import com.innbucks.userservice.repository.PendingRegistrationRepository;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.security.OtpHasher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Who gets told about a verified phone, and when.
 *
 * <p>The bug this pins shut: the loyalty promotion used to fire only from
 * inside the two local-account branches — materialising a pending registration,
 * or flipping {@code phoneVerified} on an existing customer. A customer of the
 * mobile app authenticates against InnBucks, so they have NEITHER: no user row
 * here, no pending registration. Their OTP verified fine and loyalty was never
 * told, so their loyalty account stayed PENDING forever — earning and receiving,
 * every spend refused. That population is the entire reason this flow exists,
 * and it was the one case that silently did nothing.
 *
 * <p>The fix is to promote on EVERY successful verify, unconditionally: the OTP
 * is the proof, and it is the same proof whichever local account state happens
 * to exist.
 */
class OtpLoyaltyPromotionTest {

    private static final String PHONE = "+263771234567";
    private static final OtpHasher HASHER = new OtpHasher("test-otp-hmac-secret-unit-tests-0123456789");

    private record Fixture(OtpService service, LoyaltyServiceClient loyalty,
                           UserRepository users, CustomerProfileRepository profiles,
                           PendingRegistrationRepository pending, OtpRepository otps) {}

    private static Fixture fixture() {
        OtpRepository otpRepo = mock(OtpRepository.class);
        OtpRetryAttemptRepository retryRepo = mock(OtpRetryAttemptRepository.class);
        UserRepository userRepo = mock(UserRepository.class);
        CustomerProfileRepository profileRepo = mock(CustomerProfileRepository.class);
        PendingRegistrationRepository pendingRepo = mock(PendingRegistrationRepository.class);
        LoyaltyServiceClient loyalty = mock(LoyaltyServiceClient.class);
        OtpService service = new OtpService(otpRepo, HASHER, retryRepo, userRepo, profileRepo,
                pendingRepo, loyalty, mock(WhatsAppNotificationClient.class),
                mock(SmsNotificationClient.class), mock(EmailNotificationClient.class));
        when(otpRepo.consume(eq(PHONE), eq(HASHER.hash("000000")), any())).thenReturn(1);
        return new Fixture(service, loyalty, userRepo, profileRepo, pendingRepo, otpRepo);
    }

    @Test
    @DisplayName("APP CUSTOMER: no user row and no pending registration — loyalty is STILL told")
    void appCustomerWithNoLocalAccount_isPromoted() {
        // The regression case. Before the fix this asserted zero calls, and the
        // customer stayed PENDING in loyalty forever.
        Fixture f = fixture();
        when(f.pending().findByPhoneNumber(PHONE)).thenReturn(Optional.empty());
        when(f.users().findByPhoneNumber(PHONE)).thenReturn(Optional.empty());

        assertTrue(f.service().verifyOtp(PHONE, "000000"));

        verify(f.loyalty()).promoteUserByPhone(PHONE);
        // And no local account is invented as a side effect — the OTP proves a
        // phone, it does not enrol anyone in ticketing.
        verify(f.users(), never()).save(any());
    }

    @Test
    @DisplayName("pending registration path still promotes (exactly once, not twice)")
    void pendingRegistration_promotesOnce() {
        // Moving the call out of the branches must not double it — the branch
        // and the unconditional call would both fire otherwise.
        Fixture f = fixture();
        when(f.pending().findByPhoneNumber(PHONE)).thenReturn(Optional.of(
                PendingRegistration.builder().phoneNumber(PHONE).passwordHash("hashed-pw").build()));

        assertTrue(f.service().verifyOtp(PHONE, "000000"));

        verify(f.loyalty(), times(1)).promoteUserByPhone(PHONE);
        verify(f.users()).save(any(User.class));
    }

    @Test
    @DisplayName("existing customer path still promotes (exactly once)")
    void existingCustomer_promotesOnce() {
        Fixture f = fixture();
        when(f.pending().findByPhoneNumber(PHONE)).thenReturn(Optional.empty());
        User user = User.builder().id(7L).phoneNumber(PHONE)
                .roles(User.roleNames(User.Role.CUSTOMER)).build();
        CustomerProfile profile = CustomerProfile.builder()
                .user(user).registrationTier(1).phoneVerified(false).build();
        when(f.users().findByPhoneNumber(PHONE)).thenReturn(Optional.of(user));
        when(f.profiles().findByUserId(7L)).thenReturn(Optional.of(profile));

        assertTrue(f.service().verifyOtp(PHONE, "000000"));

        verify(f.loyalty(), times(1)).promoteUserByPhone(PHONE);
        assertTrue(profile.isPhoneVerified());
    }

    @Test
    @DisplayName("an ALREADY-verified customer is promoted too — the old code skipped them")
    void alreadyVerifiedCustomer_isStillPromoted() {
        // The narrow miss that motivated making this unconditional: promotion
        // used to be nested inside `if (!profile.isPhoneVerified())`, so a
        // customer whose phone was verified BEFORE loyalty existed (or during
        // an outage that dropped the webhook) could never be re-promoted by
        // re-verifying. It is idempotent on the loyalty side, so there is no
        // reason to withhold it.
        Fixture f = fixture();
        when(f.pending().findByPhoneNumber(PHONE)).thenReturn(Optional.empty());
        User user = User.builder().id(7L).phoneNumber(PHONE)
                .roles(User.roleNames(User.Role.CUSTOMER)).build();
        CustomerProfile profile = CustomerProfile.builder()
                .user(user).registrationTier(1).phoneVerified(true).build();
        when(f.users().findByPhoneNumber(PHONE)).thenReturn(Optional.of(user));
        when(f.profiles().findByUserId(7L)).thenReturn(Optional.of(profile));

        assertTrue(f.service().verifyOtp(PHONE, "000000"));

        verify(f.loyalty()).promoteUserByPhone(PHONE);
    }

    @Test
    @DisplayName("a FAILED verification tells loyalty nothing")
    void wrongCode_promotesNobody() {
        // The load-bearing negative: promotion must hang off the proof, never
        // off the attempt.
        Fixture f = fixture();
        when(f.otps().consume(eq(PHONE), any(), any())).thenReturn(0);
        when(f.otps().findByPhoneNumber(PHONE)).thenReturn(Optional.empty());

        assertTrue(!f.service().verifyOtp(PHONE, "999999"));

        verify(f.loyalty(), never()).promoteUserByPhone(any());
    }

    @Test
    @DisplayName("loyalty is told the CANONICAL number, whatever spelling the client sent")
    void promotesTheNormalizedNumber() {
        // loyalty keys phone_registrations by E.164, so a local-format promote
        // would register a number that joins to nothing.
        Fixture f = fixture();
        when(f.otps().consume(eq(PHONE), eq(HASHER.hash("000000")), any())).thenReturn(1);
        when(f.pending().findByPhoneNumber(PHONE)).thenReturn(Optional.empty());
        when(f.users().findByPhoneNumber(PHONE)).thenReturn(Optional.empty());

        assertTrue(f.service().verifyOtp("0771234567", "000000"));

        verify(f.loyalty()).promoteUserByPhone(PHONE);
    }

    @Test
    @DisplayName("canonicalPhone exposes the same normalization the verify keys by")
    void canonicalPhone_matchesTheVerifyKey() {
        // The token minted off a verification is scoped with this, so the two
        // must not diverge: a token naming a different spelling would assert
        // something the OTP never proved.
        assertEquals(PHONE, fixture().service().canonicalPhone("0771234567"));
        assertEquals(PHONE, fixture().service().canonicalPhone(PHONE));
    }
}
