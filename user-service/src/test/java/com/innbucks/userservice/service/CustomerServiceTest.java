package com.innbucks.userservice.service;

import com.innbucks.userservice.corebanking.CoreBankingCreateCustomerCommand;
import com.innbucks.userservice.corebanking.CoreBankingCustomerResult;
import com.innbucks.userservice.corebanking.CoreBankingPort;
import com.innbucks.userservice.dto.CustomerRegistrationResponseDTO;
import com.innbucks.userservice.dto.CustomerTier2RegisterDTO;
import com.innbucks.userservice.dto.CustomerTier4RegisterDTO;
import com.innbucks.userservice.entity.CustomerProfile;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.CustomerProfileRepository;
import com.innbucks.userservice.repository.DeviceRepository;
import com.innbucks.userservice.repository.PendingRegistrationRepository;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.security.ConsumedKycTokenStore;
import com.innbucks.userservice.security.KycVerificationTokenException;
import com.innbucks.userservice.security.KycVerificationTokenService;
import com.innbucks.userservice.security.VerifiedKycToken;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class CustomerServiceTest {

    // In these tests the verification token string IS the bound phone: the
    // stubbed KycVerificationTokenService echoes the token back as the verified
    // phone. So a call whose token equals the request's phone passes the
    // phone-match gate, and a call whose token differs models the takeover
    // attempt (attacker's token + victim's phone) the fix must reject.
    private KycVerificationTokenService echoKyc() {
        KycVerificationTokenService kyc = mock(KycVerificationTokenService.class);
        when(kyc.verify(anyString())).thenAnswer(inv -> {
            String tok = inv.getArgument(0);
            return new VerifiedKycToken(tok, "jti-" + tok, Instant.now().plusSeconds(900));
        });
        return kyc;
    }

    private CustomerService newService(UserRepository userRepo,
                                       CustomerProfileRepository profileRepo) {
        return newService(userRepo, profileRepo, mock(CoreBankingPort.class));
    }

    private CustomerService newService(UserRepository userRepo,
                                       CustomerProfileRepository profileRepo,
                                       CoreBankingPort coreBanking) {
        return newService(userRepo, profileRepo, coreBanking, echoKyc(), mock(ConsumedKycTokenStore.class));
    }

    private CustomerService newService(UserRepository userRepo,
                                       CustomerProfileRepository profileRepo,
                                       CoreBankingPort coreBanking,
                                       KycVerificationTokenService kyc,
                                       ConsumedKycTokenStore consumedStore) {
        return new CustomerService(
                userRepo,
                profileRepo,
                mock(DeviceRepository.class),
                mock(PendingRegistrationRepository.class),
                mock(PasswordEncoder.class),
                mock(OtpService.class),
                coreBanking,
                new com.innbucks.userservice.security.NationalIdHasher("test-secret"),
                kyc,
                consumedStore
        );
    }

    private CustomerTier2RegisterDTO tier2Request(String msisdn) {
        CustomerTier2RegisterDTO dto = new CustomerTier2RegisterDTO();
        dto.setFirstName("Alice");
        dto.setMiddleName("M");
        dto.setLastName("Moyo");
        dto.setMsisdn(msisdn);
        dto.setNationalId("12345678");
        dto.setEmail("alice@example.com");
        dto.setDateOfBirth(LocalDate.of(1995, 4, 12));
        dto.setGender(CustomerProfile.Gender.FEMALE);
        CustomerTier2RegisterDTO.Address addr = new CustomerTier2RegisterDTO.Address();
        addr.setStreet1("1 Main St");
        addr.setCity("Bulawayo");
        addr.setPostCode("000000");
        addr.setCountry("ZW");
        dto.setAddress(addr);
        dto.setClientCustomFields(new LinkedHashMap<>());
        return dto;
    }

    private CoreBankingCustomerResult fakeCoreBankingResult() {
        return CoreBankingCustomerResult.builder()
                .profileRef("oradian-ext-1")
                .oradianExternalId("oradian-ext-1")
                .oradianClientId(1001L)
                .status("PENDING_APPROVAL")
                .country("KE")
                .build();
    }

    private User customerUser(long id, String phone) {
        return User.builder()
                .id(id)
                .firstName("Alice")
                .lastName("Moyo")
                .phoneNumber(phone)
                .password("hashed")
                .roles(EnumSet.of(User.Role.CUSTOMER))
                .active(true)
                .build();
    }

    private CustomerTier4RegisterDTO tier4Request() {
        CustomerTier4RegisterDTO dto = new CustomerTier4RegisterDTO();
        dto.setIdDocumentPath("uploads/kyc/alice/national_id.jpg");
        dto.setProofOfResidencePath("uploads/kyc/alice/por.pdf");
        dto.setPassportDocumentPath("uploads/kyc/alice/passport.jpg");
        return dto;
    }

    @Test
    void registerTier4_marksProfileTier4_verified_andPersistsDocumentPaths() {
        UserRepository userRepo = mock(UserRepository.class);
        CustomerProfileRepository profileRepo = mock(CustomerProfileRepository.class);
        CustomerService service = newService(userRepo, profileRepo);

        User user = customerUser(42L, "+263770000001");
        CustomerProfile profile = CustomerProfile.builder()
                .user(user)
                .registrationTier(3) // must be at tier 3 to advance to tier 4
                .verified(false)
                .build();
        when(userRepo.findByPhoneNumber("+263770000001")).thenReturn(Optional.of(user));
        when(profileRepo.findByUserId(42L)).thenReturn(Optional.of(profile));

        CustomerRegistrationResponseDTO resp =
                service.registerTier4("+263770000001", tier4Request(), "+263770000001");

        ArgumentCaptor<CustomerProfile> saved = ArgumentCaptor.forClass(CustomerProfile.class);
        verify(profileRepo).save(saved.capture());
        CustomerProfile written = saved.getValue();
        assertEquals(4, written.getRegistrationTier());
        assertTrue(written.isVerified());
        assertEquals("uploads/kyc/alice/national_id.jpg", written.getIdDocumentPath());
        assertEquals("uploads/kyc/alice/por.pdf", written.getProofOfResidencePath());
        assertEquals("uploads/kyc/alice/passport.jpg", written.getPassportDocumentPath());

        assertEquals(4, resp.getTier());
        assertTrue(resp.isVerified());
        assertNull(resp.getNextStep(), "tier 4 is terminal — no next step");
    }

    @Test
    void registerTier4_rejectsWhenCustomerIsNotYetAtTier3() {
        UserRepository userRepo = mock(UserRepository.class);
        CustomerProfileRepository profileRepo = mock(CustomerProfileRepository.class);
        CustomerService service = newService(userRepo, profileRepo);

        User user = customerUser(42L, "+263770000001");
        CustomerProfile profile = CustomerProfile.builder()
                .user(user)
                .registrationTier(2) // skipped tier 3 — must be rejected
                .build();
        when(userRepo.findByPhoneNumber("+263770000001")).thenReturn(Optional.of(user));
        when(profileRepo.findByUserId(42L)).thenReturn(Optional.of(profile));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.registerTier4("+263770000001", tier4Request(), "+263770000001"));
        assertTrue(ex.getMessage().contains("tier 3"),
                "expected tier-3 prerequisite message, got: " + ex.getMessage());
        verify(profileRepo, never()).save(any());
    }

    @Test
    void registerTier2_usesStableIdempotencyKeyDerivedFromUserId() {
        // Pins the contract that makes Oradian-vs-local atomicity recoverable:
        // the idempotency key MUST be derived from User.id, never randomised
        // per call. If anything between the Oradian response and the local
        // transaction commit fails, the @Transactional rolls back and the
        // FE retries — and the retry must replay Oradian's cached response
        // (same key, same body), not double-create a fresh client.
        UserRepository userRepo = mock(UserRepository.class);
        CustomerProfileRepository profileRepo = mock(CustomerProfileRepository.class);
        CoreBankingPort coreBanking = mock(CoreBankingPort.class);
        when(coreBanking.provider()).thenReturn("ORADIAN");
        when(coreBanking.createCustomer(any(CoreBankingCreateCustomerCommand.class), anyString()))
                .thenReturn(fakeCoreBankingResult());
        CustomerService service = newService(userRepo, profileRepo, coreBanking);

        User user = customerUser(42L, "+263770000001");
        CustomerProfile profile = CustomerProfile.builder()
                .user(user)
                .registrationTier(1)
                .build();
        when(userRepo.findByPhoneNumber("+263770000001")).thenReturn(Optional.of(user));
        when(profileRepo.findByUserId(42L)).thenReturn(Optional.of(profile));

        service.registerTier2(tier2Request("+263770000001"), "+263770000001");

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(coreBanking).createCustomer(any(CoreBankingCreateCustomerCommand.class), keyCaptor.capture());
        String key = keyCaptor.getValue();

        assertEquals("customer-tier-2:42", key,
                "idempotency key must be derived from User.id so retries replay the provider's cached response");

        // The provider-agnostic linkage AND the legacy oradian columns must
        // both land on the profile (dual-write during the provider split).
        assertEquals("ORADIAN", profile.getCoreBankingProvider());
        assertEquals("oradian-ext-1", profile.getCoreBankingProfileId());
        assertEquals("oradian-ext-1", profile.getOradianExternalId());
        assertEquals(1001L, profile.getOradianClientId());
    }

    @Test
    void registerTier2_storesHashedNationalId_butSendsRawToCoreBanking() {
        // PII at rest: the stored national_id must be HMAC'd, never the raw
        // "12345678". But core-banking (Oradian) needs the REAL id for KYC, and
        // it gets it straight off the request — proving the hash is storage-only
        // and doesn't corrupt the provider linkage.
        UserRepository userRepo = mock(UserRepository.class);
        CustomerProfileRepository profileRepo = mock(CustomerProfileRepository.class);
        CoreBankingPort coreBanking = mock(CoreBankingPort.class);
        when(coreBanking.provider()).thenReturn("ORADIAN");
        when(coreBanking.createCustomer(any(CoreBankingCreateCustomerCommand.class), anyString()))
                .thenReturn(fakeCoreBankingResult());
        CustomerService service = newService(userRepo, profileRepo, coreBanking);

        User user = customerUser(42L, "+263770000001");
        CustomerProfile profile = CustomerProfile.builder()
                .user(user)
                .registrationTier(1)
                .build();
        when(userRepo.findByPhoneNumber("+263770000001")).thenReturn(Optional.of(user));
        when(profileRepo.findByUserId(42L)).thenReturn(Optional.of(profile));

        service.registerTier2(tier2Request("+263770000001"), "+263770000001");

        // Stored copy is hashed.
        assertTrue(profile.getNationalId().startsWith("hmac:"),
                "national_id must be HMAC'd at rest, was: " + profile.getNationalId());
        assertNotEquals("12345678", profile.getNationalId());

        // Core-banking still receives the raw value.
        ArgumentCaptor<CoreBankingCreateCustomerCommand> cmd =
                ArgumentCaptor.forClass(CoreBankingCreateCustomerCommand.class);
        verify(coreBanking).createCustomer(cmd.capture(), anyString());
        assertEquals("12345678", cmd.getValue().nationalId(),
                "core-banking must get the real national ID, not the hash");
    }

    @Test
    void registerTier2_passesSameKeyOnRetryForSameCustomer() {
        // Simulates the bug scenario: the first attempt's @Transactional rolls
        // back AFTER Oradian successfully committed, so the profile stays at
        // tier 1 locally. The user retries. The second call MUST send the
        // same idempotency key (same user.id => same key) so Oradian replies
        // from cache with the existing externalID / clientID.
        UserRepository userRepo = mock(UserRepository.class);
        CustomerProfileRepository profileRepo = mock(CustomerProfileRepository.class);
        CoreBankingPort coreBanking = mock(CoreBankingPort.class);
        when(coreBanking.provider()).thenReturn("ORADIAN");
        when(coreBanking.createCustomer(any(CoreBankingCreateCustomerCommand.class), anyString()))
                .thenReturn(fakeCoreBankingResult());
        CustomerService service = newService(userRepo, profileRepo, coreBanking);

        User user = customerUser(99L, "+263770000099");
        CustomerProfile profile = CustomerProfile.builder()
                .user(user)
                .registrationTier(1)
                .build();
        when(userRepo.findByPhoneNumber("+263770000099")).thenReturn(Optional.of(user));
        when(profileRepo.findByUserId(99L)).thenReturn(Optional.of(profile));

        // First attempt — the provider commits, then imagine local rollback.
        service.registerTier2(tier2Request("+263770000099"), "+263770000099");
        profile.setRegistrationTier(1);

        // Retry.
        service.registerTier2(tier2Request("+263770000099"), "+263770000099");

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(coreBanking, times(2))
                .createCustomer(any(CoreBankingCreateCustomerCommand.class), keyCaptor.capture());
        assertEquals(2, keyCaptor.getAllValues().size());
        assertEquals(keyCaptor.getAllValues().get(0), keyCaptor.getAllValues().get(1),
                "two attempts for the same user must use the same idempotency key");
        assertEquals("customer-tier-2:99", keyCaptor.getAllValues().get(0));
    }

    @Test
    void registerTier4_rejectsWhenUserIsNotACustomer() {
        UserRepository userRepo = mock(UserRepository.class);
        CustomerProfileRepository profileRepo = mock(CustomerProfileRepository.class);
        CustomerService service = newService(userRepo, profileRepo);

        User shopAdmin = User.builder()
                .id(7L)
                .firstName("S")
                .lastName("A")
                .phoneNumber("+263770000002")
                .password("hashed")
                .roles(EnumSet.of(User.Role.SHOP_ADMIN))
                .active(true)
                .build();
        when(userRepo.findByPhoneNumber("+263770000002")).thenReturn(Optional.of(shopAdmin));

        assertThrows(RuntimeException.class,
                () -> service.registerTier4("+263770000002", tier4Request(), "+263770000002"));
        verify(profileRepo, never()).save(any());
    }

    // --- C1: verification-token gate on the tier-upgrade endpoints ----------

    @Test
    void registerTier2_rejectsWhenTokenPhoneDiffersFromRequestedMsisdn() {
        // The takeover attempt: an attacker holds a valid verification token for
        // their OWN phone (...999) but puts a victim's msisdn (...001) in the
        // body, trying to rewrite the victim's KYC + email. The token's bound
        // phone is authoritative, so the mismatch must be rejected BEFORE any DB
        // read or write.
        UserRepository userRepo = mock(UserRepository.class);
        CustomerProfileRepository profileRepo = mock(CustomerProfileRepository.class);
        CustomerService service = newService(userRepo, profileRepo);

        KycVerificationTokenException ex = assertThrows(KycVerificationTokenException.class,
                () -> service.registerTier2(tier2Request("+263770000001"), "+263770000999"));

        assertEquals(KycVerificationTokenException.Reason.PHONE_MISMATCH, ex.getReason());
        verify(profileRepo, never()).save(any());
        verify(userRepo, never()).save(any());
    }

    @Test
    void registerTier3_rejectsWhenTokenPhoneDiffersFromParam() {
        UserRepository userRepo = mock(UserRepository.class);
        CustomerProfileRepository profileRepo = mock(CustomerProfileRepository.class);
        CustomerService service = newService(userRepo, profileRepo);

        com.innbucks.userservice.dto.CustomerTier3RegisterDTO req =
                new com.innbucks.userservice.dto.CustomerTier3RegisterDTO();

        KycVerificationTokenException ex = assertThrows(KycVerificationTokenException.class,
                () -> service.registerTier3("+263770000001", req, "+263770000999"));

        assertEquals(KycVerificationTokenException.Reason.PHONE_MISMATCH, ex.getReason());
        verify(profileRepo, never()).save(any());
    }

    @Test
    void registerTier4_rejectsReplayedToken_andDoesNotFlipVerified() {
        // Single-use: the terminal "mark verified" step consumes the token's jti.
        // A replay (same token presented again) collides in the consume store and
        // must be rejected — and the verified flip must NOT persist.
        UserRepository userRepo = mock(UserRepository.class);
        CustomerProfileRepository profileRepo = mock(CustomerProfileRepository.class);
        ConsumedKycTokenStore store = mock(ConsumedKycTokenStore.class);
        doThrow(new KycVerificationTokenException(
                KycVerificationTokenException.Reason.REPLAYED, "verification token already used"))
                .when(store).consume(anyString(), any());
        CustomerService service =
                newService(userRepo, profileRepo, mock(CoreBankingPort.class), echoKyc(), store);

        User user = customerUser(42L, "+263770000001");
        CustomerProfile profile = CustomerProfile.builder()
                .user(user)
                .registrationTier(3)
                .verified(false)
                .build();
        when(userRepo.findByPhoneNumber("+263770000001")).thenReturn(Optional.of(user));
        when(profileRepo.findByUserId(42L)).thenReturn(Optional.of(profile));

        KycVerificationTokenException ex = assertThrows(KycVerificationTokenException.class,
                () -> service.registerTier4("+263770000001", tier4Request(), "+263770000001"));

        assertEquals(KycVerificationTokenException.Reason.REPLAYED, ex.getReason());
        verify(store).consume(eq("jti-+263770000001"), any());
        verify(profileRepo, never()).save(any());
        assertFalse(profile.isVerified(), "a replayed token must not flip the profile to verified");
    }
}
