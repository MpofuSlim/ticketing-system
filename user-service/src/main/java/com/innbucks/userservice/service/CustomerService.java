package com.innbucks.userservice.service;

import com.innbucks.userservice.dto.*;
import com.innbucks.userservice.entity.CustomerProfile;
import com.innbucks.userservice.entity.Device;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.CustomerProfileRepository;
import com.innbucks.userservice.repository.DeviceRepository;
import com.innbucks.userservice.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class CustomerService {

    private final UserRepository userRepository;
    private final CustomerProfileRepository customerProfileRepository;
    private final DeviceRepository deviceRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public CustomerRegistrationResponseDTO registerTier1(CustomerTier1RegisterDTO request) {
        log.info("Customer tier 1 registration phone={}", request.getPhoneNumber());
        if (userRepository.existsByPhoneNumber(request.getPhoneNumber())) {
            throw new RuntimeException("Phone number already registered");
        }

        User user = User.builder()
                .firstName("Customer")
                .lastName("Pending")
                .phoneNumber(request.getPhoneNumber())
                .password(passwordEncoder.encode(request.getPassword()))
                .role(User.Role.CUSTOMER)
                .mfaEnabled(false)
                .build();
        userRepository.save(user);

        CustomerProfile profile = CustomerProfile.builder()
                .user(user)
                .registrationTier(1)
                .verified(false)
                .build();
        customerProfileRepository.save(profile);

        return CustomerRegistrationResponseDTO.builder()
                .userId(user.getId())
                .phoneNumber(user.getPhoneNumber())
                .tier(1)
                .verified(false)
                .nextStep("Submit personal details at /auth/customer/register/tier2")
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
        profile.setSelfiePicturePath(request.getSelfiePicturePath());
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
