package com.innbucks.userservice.service;

import com.innbucks.userservice.dto.AgentProfileDTO;
import com.innbucks.userservice.entity.AgentProfile;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.AgentProfileRepository;
import com.innbucks.userservice.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AgentService {

    private final AgentProfileRepository agentProfileRepository;
    private final UserRepository userRepository;

    public AgentProfileDTO getProfile(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));

        AgentProfile profile = agentProfileRepository.findByUserId(user.getId())
                .orElseThrow(() -> new RuntimeException("Agent profile not found"));

        return AgentProfileDTO.builder()
                .id(profile.getId())
                .businessName(profile.getBusinessName())
                .totalEvents(profile.getTotalEvents())
                .rating(profile.getRating())
                .build();
    }
}
