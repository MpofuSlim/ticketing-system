package com.innbucks.userservice.testsupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.innbucks.userservice.entity.Organization;
import com.innbucks.userservice.entity.OrganizationMember;
import com.innbucks.userservice.entity.ServiceRequest;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.OrganizationMemberRepository;
import com.innbucks.userservice.repository.OrganizationRepository;
import com.innbucks.userservice.repository.ServiceRequestRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Customer-support ITs (V45/V46): real Postgres, the real HTTP surface, and
 * REAL sessions — every agent signs in with password + TOTP and calls with the
 * bearer token, so the permissions under test are the ones V46 actually grants
 * the call-center built-ins, resolved through JwtFilter exactly as in production.
 */
public abstract class SupportItSupport extends StaffItSupport {

    @Autowired protected OrganizationRepository organizationRepository;
    @Autowired protected OrganizationMemberRepository memberRepository;
    @Autowired protected ServiceRequestRepository serviceRequestRepository;

    /**
     * A real session for an ENROLLED account (password, then TOTP); returns
     * "Bearer …". Signs in from its own forwarded address: the login limiter's
     * per-IP window is shared by every IT in a cached context (CI has no Redis,
     * so it is the in-memory window), and these ITs sign many agents in — they
     * must not spend the budget the other ITs' 127.0.0.1 sign-ins rely on.
     */
    protected String session(User account) throws Exception {
        String ip = "10.77." + java.util.concurrent.ThreadLocalRandom.current().nextInt(256) + "."
                + java.util.concurrent.ThreadLocalRandom.current().nextInt(1, 255);
        String mfaToken = data(mockMvc.perform(post("/auth/login")
                        .header("X-Device-Id", DEVICE).header("X-Forwarded-For", ip)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("identifier", account.getEmail(),
                                "password", PASSWORD))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()))
                .at("/mfaToken").asText();
        JsonNode session = data(mockMvc.perform(post("/auth/login/mfa")
                        .header("X-Device-Id", DEVICE).header("X-Forwarded-For", ip)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("mfaToken", mfaToken,
                                "code", totp(TOTP_SECRET)))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()));
        return "Bearer " + session.at("/token").asText();
    }

    /**
     * A merchant with a Foundry console account: MERCHANT_ADMIN, OWNER of a
     * business, enrolled in 2FA, locked out after five wrong passwords, with a
     * PENDING service request.
     */
    protected User lockedMerchant() {
        User merchant = users.save(User.builder()
                .firstName("Tariro").lastName("Moyo")
                .email("merchant-" + unique() + "@example.com")
                .phoneNumber(phone())
                .password(passwordEncoder.encode(PASSWORD))
                .roles(new LinkedHashSet<>(List.of(User.Role.MERCHANT_ADMIN.name())))
                .active(true).approved(true)
                .mfaEnabled(true).mfaSecret(TOTP_SECRET)
                .failedLoginAttempts(5)
                .lockedUntil(Instant.now().plus(20, ChronoUnit.MINUTES))
                .build());
        Organization org = organizationRepository.save(Organization.builder()
                .id(UUID.randomUUID()).name("Moyo Fresh Foods " + unique()).createdByUserId(merchant.getId()).build());
        memberRepository.save(OrganizationMember.builder().id(UUID.randomUUID()).organizationId(org.getId())
                .userId(merchant.getId()).role(OrganizationMember.Role.OWNER).build());
        serviceRequestRepository.save(ServiceRequest.builder().userId(merchant.getId()).service("marketplace")
                .reason("We want to sell online.").status(ServiceRequest.Status.PENDING).build());
        return merchant;
    }

    protected ResultActions search(String bearer, String q) throws Exception {
        return mockMvc.perform(post("/admin/support/customers/search")
                .header("Authorization", bearer)
                .header("X-Forwarded-For", "41.221.147.12")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("q", q))));
    }

    protected String lookupId(String bearer, String q) throws Exception {
        return data(search(bearer, q)).at("/lookupId").asText();
    }

    protected ResultActions write(String bearer, Long userId, String action, String lookupId, String key)
            throws Exception {
        var request = post("/admin/support/console-users/{id}/" + action, userId)
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "lookupId", lookupId == null ? "" : lookupId,
                        "note", "Caller verified by date of birth and last sign-in time.")));
        if (key != null) request.header("Idempotency-Key", key);
        return mockMvc.perform(request);
    }

    protected ResultActions detail(String bearer, Long userId, String lookupId) throws Exception {
        var request = get("/admin/support/console-users/{id}", userId).header("Authorization", bearer);
        if (lookupId != null) request.param("lookupId", lookupId);
        return mockMvc.perform(request);
    }

    protected JsonNode firstConsoleAccount(JsonNode searchData) {
        return searchData.at("/sections/console/data/accounts/0");
    }
}
