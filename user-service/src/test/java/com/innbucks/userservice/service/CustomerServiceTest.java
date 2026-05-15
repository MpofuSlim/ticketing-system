package com.innbucks.userservice.service;

import com.innbucks.userservice.client.OradianClient;
import com.innbucks.userservice.dto.CustomerRegistrationResponseDTO;
import com.innbucks.userservice.dto.CustomerTier4RegisterDTO;
import com.innbucks.userservice.entity.CustomerProfile;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.CustomerProfileRepository;
import com.innbucks.userservice.repository.DeviceRepository;
import com.innbucks.userservice.repository.PendingRegistrationRepository;
import com.innbucks.userservice.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.EnumSet;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
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
                mock(OradianClient.class)
        );
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
                () -> service.registerTier4("+263770000001", tier4Request()));
        assertTrue(ex.getMessage().contains("tier 3"),
                "expected tier-3 prerequisite message, got: " + ex.getMessage());
        verify(profileRepo, never()).save(any());
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
                () -> service.registerTier4("+263770000002", tier4Request()));
        verify(profileRepo, never()).save(any());
    }
}
