package com.innbucks.userservice.service;

import com.innbucks.userservice.dto.CustomerRegistrationResponseDTO;
import com.innbucks.userservice.dto.CustomerTier2RegisterDTO;
import com.innbucks.userservice.dto.CustomerTier4RegisterDTO;
import com.innbucks.userservice.dto.CustomerTierResponseDTO;
import com.innbucks.userservice.entity.CustomerProfile;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.CustomerProfileRepository;
import com.innbucks.userservice.repository.DeviceRepository;
import com.innbucks.userservice.repository.PendingRegistrationRepository;
import com.innbucks.userservice.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class CustomerServiceTest {

    private CustomerService newService(UserRepository userRepo,
                                       CustomerProfileRepository profileRepo) {
        return new CustomerService(
                userRepo,
                profileRepo,
                mock(DeviceRepository.class),
                mock(PendingRegistrationRepository.class),
                mock(PasswordEncoder.class),
                mock(OtpService.class),
                new com.innbucks.userservice.security.NationalIdHasher("test-secret")
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

    private User customerUser(long id, String phone) {
        return User.builder()
                .id(id)
                .firstName("Alice")
                .lastName("Moyo")
                .phoneNumber(phone)
                .password("hashed")
                .roles(User.roleNames(User.Role.CUSTOMER))
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
                // A01/A04: recent OTP verification — the tier2/3/4 gate requires it.
                .phoneVerifiedAt(LocalDateTime.now(ZoneOffset.UTC))
                .build();
        when(userRepo.findByPhoneNumber("+263770000001")).thenReturn(Optional.of(user));
        when(profileRepo.findByUserId(42L)).thenReturn(Optional.of(profile));

        CustomerRegistrationResponseDTO resp = service.registerTier4("+263770000001", tier4Request());

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
    void registerTier4_isAcceptedEvenWhenEarlierTiersWereSkipped() {
        // The registration ladder is no longer ordered. This test previously
        // asserted the opposite — that a profile still at tier 2 was refused
        // with "Please complete tier 3 registration first." That rejection was
        // the last tier check a customer could hit anywhere in the fleet, and
        // it is deliberately gone.
        //
        // What this pins now is the consequence, so it is visible rather than
        // discovered: the profile advances to tier 4 with the tier-3 step never
        // taken. A KYC report that assumes "tier 4 implies every earlier tier
        // was completed" is no longer safe to write.
        UserRepository userRepo = mock(UserRepository.class);
        CustomerProfileRepository profileRepo = mock(CustomerProfileRepository.class);
        CustomerService service = newService(userRepo, profileRepo);

        User user = customerUser(42L, "+263770000001");
        CustomerProfile profile = CustomerProfile.builder()
                .user(user)
                .registrationTier(2) // tier 3 skipped — no longer refused
                // The OTP-recency guard is unrelated to tier and still applies.
                .phoneVerifiedAt(LocalDateTime.now(ZoneOffset.UTC))
                .build();
        when(userRepo.findByPhoneNumber("+263770000001")).thenReturn(Optional.of(user));
        when(profileRepo.findByUserId(42L)).thenReturn(Optional.of(profile));

        service.registerTier4("+263770000001", tier4Request());

        assertEquals(4, profile.getRegistrationTier(),
                "tier 4 must be reachable directly from tier 2");
        verify(profileRepo, atLeastOnce()).save(any());
    }

    @Test
    void registerTier2_storesHashedNationalId_neverTheRawValue() {
        // PII at rest: the stored national_id must be HMAC'd, never the raw
        // "12345678". This used to also assert that core-banking received the
        // RAW id for KYC — that half went away with the Oradian mirror, but the
        // storage guarantee is the part that protects the database and it still
        // holds.
        UserRepository userRepo = mock(UserRepository.class);
        CustomerProfileRepository profileRepo = mock(CustomerProfileRepository.class);
        CustomerService service = newService(userRepo, profileRepo);

        User user = customerUser(42L, "+263770000001");
        CustomerProfile profile = CustomerProfile.builder()
                .user(user)
                .registrationTier(1)
                // A01/A04: recent OTP verification — the tier2/3/4 gate requires it.
                .phoneVerifiedAt(LocalDateTime.now(ZoneOffset.UTC))
                .build();
        when(userRepo.findByPhoneNumber("+263770000001")).thenReturn(Optional.of(user));
        when(profileRepo.findByUserId(42L)).thenReturn(Optional.of(profile));

        service.registerTier2(tier2Request("+263770000001"));

        // Stored copy is hashed.
        assertTrue(profile.getNationalId().startsWith("hmac:"),
                "national_id must be HMAC'd at rest, was: " + profile.getNationalId());
        assertNotEquals("12345678", profile.getNationalId());
    }

    @Test
    void registerTier2_rejectsWhenPhoneNotRecentlyVerified() {
        // A01/A04 account-takeover guard: a tier-2 upgrade for a profile whose
        // phone was never OTP-verified (phoneVerifiedAt == null) MUST be refused
        // with 403 — otherwise an unauthenticated attacker could overwrite a
        // victim's email/KYC just by naming their phone in the request body. The
        // gate fires before any local profile write.
        UserRepository userRepo = mock(UserRepository.class);
        CustomerProfileRepository profileRepo = mock(CustomerProfileRepository.class);
        CustomerService service = newService(userRepo, profileRepo);

        User user = customerUser(55L, "+263770000055");
        CustomerProfile profile = CustomerProfile.builder()
                .user(user)
                .registrationTier(1)
                .phoneVerified(false) // never OTP-verified => no recency stamp
                .build();             // phoneVerifiedAt deliberately left null
        when(userRepo.findByPhoneNumber("+263770000055")).thenReturn(Optional.of(user));
        when(profileRepo.findByUserId(55L)).thenReturn(Optional.of(profile));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.registerTier2(tier2Request("+263770000055")));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        assertTrue(ex.getReason() != null && ex.getReason().contains("Verify your phone"),
                "expected the phone-verification-required reason, got: " + ex.getReason());

        // Gate short-circuits before any save, so an attacker can't advance
        // the tier or overwrite the victim's KYC fields.
        verify(profileRepo, never()).save(any());
        assertEquals(1, profile.getRegistrationTier(), "tier must not advance");
    }

    @Test
    void getCustomerTierByPhoneNumber_returnsTierProgression_withoutLeakingEmail() throws Exception {
        // OWASP A01 information-exposure guard. GET /auth/customer/tier is PUBLIC
        // and keyed only by a phone number so the mobile app can route the
        // pre-login registration flow. It must return the non-sensitive tier
        // fields ONLY — never the customer's email, or it becomes an
        // unauthenticated phone -> email harvesting / account-existence oracle.
        UserRepository userRepo = mock(UserRepository.class);
        CustomerProfileRepository profileRepo = mock(CustomerProfileRepository.class);
        CustomerService service = newService(userRepo, profileRepo);

        User user = customerUser(42L, "+263770000001");
        user.setEmail("victim@example.com"); // PII that must NOT reach an unauthenticated caller
        CustomerProfile profile = CustomerProfile.builder()
                .user(user)
                .registrationTier(2)
                .build();
        when(userRepo.findByPhoneNumber("+263770000001")).thenReturn(Optional.of(user));
        when(profileRepo.findByUserId(42L)).thenReturn(Optional.of(profile));

        CustomerTierResponseDTO resp = service.getCustomerTierByPhoneNumber("+263770000001");

        // Non-sensitive registration-funnel fields remain.
        assertEquals("+263770000001", resp.getPhoneNumber());
        assertEquals(2, resp.getCurrentTier());
        assertEquals(3, resp.getNextTier());

        // The response, as it goes on the wire, carries neither an "email"
        // property nor the address value. Serialise the exact JSON the endpoint
        // returns and assert the leak is closed. This fails the build if anyone
        // re-adds an email field to CustomerTierResponseDTO.
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(resp);
        assertFalse(json.toLowerCase().contains("email"),
                "public tier response must not expose an email property: " + json);
        assertFalse(json.contains("victim@example.com"),
                "public tier response must not leak the customer's email: " + json);
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
                .roles(User.roleNames(User.Role.SHOP_ADMIN))
                .active(true)
                .build();
        when(userRepo.findByPhoneNumber("+263770000002")).thenReturn(Optional.of(shopAdmin));

        assertThrows(RuntimeException.class,
                () -> service.registerTier4("+263770000002", tier4Request()));
        verify(profileRepo, never()).save(any());
    }
}
