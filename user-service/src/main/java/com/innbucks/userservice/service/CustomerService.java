package com.innbucks.userservice.service;

import com.innbucks.userservice.dto.*;
import com.innbucks.userservice.entity.CustomerProfile;
import com.innbucks.userservice.entity.Device;
import com.innbucks.userservice.entity.PendingRegistration;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.CustomerProfileRepository;
import com.innbucks.userservice.repository.DeviceRepository;
import com.innbucks.userservice.repository.PendingRegistrationRepository;
import com.innbucks.userservice.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

@Service
@RequiredArgsConstructor
@Slf4j
public class CustomerService {

    static final Duration PENDING_REGISTRATION_TTL = Duration.ofMinutes(30);

    private final UserRepository userRepository;
    private final CustomerProfileRepository customerProfileRepository;
    private final DeviceRepository deviceRepository;
    private final PendingRegistrationRepository pendingRegistrationRepository;
    private final PasswordEncoder passwordEncoder;
    private final OtpService otpService;

    /**
     * Tier 1 no longer creates a User or CustomerProfile. It stashes the phone + hashed password
     * in a pending_registrations row and fires an OTP. The account is materialised later by
     * {@link OtpService#verifyOtp} once the customer submits a valid code.
     */
    @Transactional
    public CustomerRegistrationResponseDTO registerTier1(CustomerTier1RegisterDTO request) {
        log.info("Customer tier 1 registration phone={}", request.getPhoneNumber());
        if (userRepository.existsByPhoneNumber(request.getPhoneNumber())) {
            throw new RuntimeException("Phone number already registered");
        }

        // Replace any in-flight pending registration — lets users recover from a mistyped password.
        pendingRegistrationRepository.deleteByPhoneNumber(request.getPhoneNumber());
        pendingRegistrationRepository.flush();

        Instant now = Instant.now();
        PendingRegistration pending = PendingRegistration.builder()
                .phoneNumber(request.getPhoneNumber())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .createdAt(now)
                .expiresAt(now.plus(PENDING_REGISTRATION_TTL))
                .build();
        pendingRegistrationRepository.save(pending);

        otpService.sendOtp(request.getPhoneNumber());

        return CustomerRegistrationResponseDTO.builder()
                .phoneNumber(request.getPhoneNumber())
                .tier(1)
                .verified(false)
                .nextStep("Verify your phone at /auth/otp/verify to complete account creation, then proceed to /auth/customer/register/tier2")
                .build();
    }

    @Transactional
    public CustomerRegistrationResponseDTO registerTier2(String phoneNumber, CustomerTier2RegisterDTO request) {
        CustomerProfile profile = loadProfile(phoneNumber, 1);

        profile.setFullName(request.getFullName());
        profile.setIdNumber(request.getIdNumber());
        profile.setPassportNumber(request.getPassportNumber());
        profile.setAddress(request.getAddress());
        profile.setGender(request.getGender());
        profile.setSelfiePicture(request.getSelfiePicture());
        profile.setRegistrationTier(2);
        customerProfileRepository.save(profile);

        // Keep the User first/last name consistent with the submitted full name
        String[] parts = request.getFullName().trim().split("\\s+", 2);
        User user = profile.getUser();
        user.setFirstName(parts[0]);
        if (parts.length > 1) {
            user.setLastName(parts[1]);
        }
        userRepository.save(user);

        return CustomerRegistrationResponseDTO.builder()
                .userId(user.getId())
                .phoneNumber(user.getPhoneNumber())
                .tier(2)
                .verified(profile.isVerified())
                .nextStep("Submit biometrics and device registration at /auth/customer/register/tier3")
                .build();
    }

    @Transactional
    public CustomerRegistrationResponseDTO registerTier3(String phoneNumber, CustomerTier3RegisterDTO request) {
        CustomerProfile profile = loadProfile(phoneNumber, 2);

        profile.setBiometricsReference(request.getBiometricsReference());
        profile.setRegistrationTier(3);
        customerProfileRepository.save(profile);

        User user = profile.getUser();
        DeviceRegistrationDTO deviceDto = request.getDevice();
        Device device = deviceRepository.findByUserIdAndDeviceId(user.getId(), deviceDto.getDeviceId())
                .orElseGet(() -> Device.builder().user(user).deviceId(deviceDto.getDeviceId()).build());
        device.setDeviceName(deviceDto.getDeviceName());
        device.setPlatform(deviceDto.getPlatform());
        device.setPushToken(deviceDto.getPushToken());
        deviceRepository.save(device);

        return CustomerRegistrationResponseDTO.builder()
                .userId(user.getId())
                .phoneNumber(user.getPhoneNumber())
                .tier(3)
                .verified(profile.isVerified())
                .nextStep("Upload verification documents at /auth/customer/register/tier4")
                .build();
    }

    @Transactional
    public CustomerRegistrationResponseDTO registerTier4(String phoneNumber, CustomerTier4RegisterDTO request) {
        CustomerProfile profile = loadProfile(phoneNumber, 3);

        profile.setIdDocumentPath(request.getIdDocumentPath());
        profile.setProofOfResidencePath(request.getProofOfResidencePath());
        profile.setPassportDocumentPath(request.getPassportDocumentPath());
        profile.setRegistrationTier(4);
        profile.setVerified(true);
        customerProfileRepository.save(profile);

        return CustomerRegistrationResponseDTO.builder()
                .userId(profile.getUser().getId())
                .phoneNumber(profile.getUser().getPhoneNumber())
                .tier(4)
                .verified(true)
                .nextStep(null)
                .build();
    }

    private CustomerProfile loadProfile(String phoneNumber, int requiredCurrentTier) {
        User user = userRepository.findByPhoneNumber(phoneNumber)
                .orElseThrow(() -> new RuntimeException("Customer not found for phone " + phoneNumber));
        if (user.getRole() != User.Role.CUSTOMER) {
            throw new RuntimeException("User is not a customer");
        }
        CustomerProfile profile = customerProfileRepository.findByUserId(user.getId())
                .orElseThrow(() -> new RuntimeException("Customer profile not found"));
        if (profile.getRegistrationTier() < requiredCurrentTier) {
            throw new RuntimeException("Customer must complete tier " + requiredCurrentTier + " first");
        }
        return profile;
    }

}
