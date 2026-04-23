package com.innbucks.userservice.service;

import com.innbucks.userservice.dto.TenantProfileDTO;
import com.innbucks.userservice.entity.TenantProfile;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.TenantProfileRepository;
import com.innbucks.userservice.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class TenantService {

    private final TenantProfileRepository tenantProfileRepository;
    private final UserRepository userRepository;

    public TenantProfileDTO getProfile(String email) {
        log.debug("Fetching tenant profile email={}", email);

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> {
                    log.warn("Tenant profile lookup failed, user not found email={}", email);
                    return new RuntimeException("User not found");
                });

        TenantProfile profile = tenantProfileRepository.findByUserId(user.getId())
                .orElseThrow(() -> {
                    log.warn("Tenant profile missing for user userId={} email={}", user.getId(), email);
                    return new RuntimeException("Tenant profile not found");
                });

        log.debug("Tenant profile returned userId={} tenantProfileId={}", user.getId(), profile.getId());
        return TenantProfileDTO.builder()
                .id(profile.getId())
                .businessName(profile.getBusinessName())
                .totalEvents(profile.getTotalEvents())
                .rating(profile.getRating())
                .build();
    }
}
