package com.innbucks.userservice.service;

import com.innbucks.userservice.dto.AgentProfileDTO;
import com.innbucks.userservice.entity.AgentProfile;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.AgentProfileRepository;
import com.innbucks.userservice.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class AgentService {

    private final AgentProfileRepository agentProfileRepository;
    private final UserRepository userRepository;

    public AgentProfileDTO getProfile(String email) {
        log.debug("Fetching agent profile email={}", email);

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> {
                    log.warn("Agent profile lookup failed, user not found email={}", email);
                    return new RuntimeException("User not found");
                });

        AgentProfile profile = agentProfileRepository.findByUserId(user.getId())
                .orElseThrow(() -> {
                    log.warn("Agent profile missing for user userId={} email={}", user.getId(), email);
                    return new RuntimeException("Agent profile not found");
                });

        log.debug("Agent profile returned userId={} agentProfileId={}", user.getId(), profile.getId());
        return AgentProfileDTO.builder()
                .id(profile.getId())
                .businessName(profile.getBusinessName())
                .totalEvents(profile.getTotalEvents())
                .rating(profile.getRating())
                .build();
    }
}
