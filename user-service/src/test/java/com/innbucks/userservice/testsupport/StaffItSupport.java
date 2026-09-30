package com.innbucks.userservice.testsupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.innbucks.common.email.BrandedEmailRenderer;
import com.innbucks.userservice.client.EmailNotificationClient;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Staff-account (V44) ITs: real Postgres, the real HTTP surface, and the real
 * invite mailer — only the email TRANSPORT is a mock, so the link a test redeems
 * is exactly the one the mailer built (after commit, off the request thread).
 */
public abstract class StaffItSupport extends SessionRevocationItSupport {

    @MockitoBean protected EmailNotificationClient email;

    @BeforeEach
    void anEmailTransportIsConfigured() {
        // POST /admin/staff answers 503 staff_invites_unconfigured without one.
        when(email.apiConfigured()).thenReturn(true);
    }

    /** The platform owner, holding the staff and admin permissions the endpoints check. */
    protected static UsernamePasswordAuthenticationToken staffAdmin() {
        return new UsernamePasswordAuthenticationToken(ADMIN_EMAIL, null, List.of(
                new SimpleGrantedAuthority("staff:create"), new SimpleGrantedAuthority("staff:read"),
                new SimpleGrantedAuthority("staff:manage"), new SimpleGrantedAuthority("users:roles:write"),
                new SimpleGrantedAuthority("users:activation:write"), new SimpleGrantedAuthority("users:read"),
                new SimpleGrantedAuthority("roles:read"), new SimpleGrantedAuthority("roles:write"),
                new SimpleGrantedAuthority("organizations:manage"), new SimpleGrantedAuthority("organizations:read")));
    }

    /** {@code POST /admin/staff}; returns the new account's id. {@code phone} may be null. */
    protected long createStaff(String address, String phone, String... roles) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("firstName", "Tariro");
        body.put("lastName", "Moyo");
        body.put("email", address);
        if (phone != null) body.put("phoneNumber", phone);
        body.put("country", "Zimbabwe");
        body.put("roles", List.of(roles));
        body.put("note", "Joins the Harare call-center team on 1 Oct (HR-2291).");
        return data(mockMvc.perform(post("/admin/staff").with(authentication(staffAdmin()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("INVITED")))
                .at("/id").asLong();
    }

    /** A unique address on an allowed staff domain. */
    protected static String staffAddress() {
        return "staff." + unique() + "@innbucks.co.zw";
    }

    /** Every invite link the mailer has sent to {@code address}, oldest first (waits for the async send). */
    protected List<String> inviteTokensSentTo(String address) {
        ArgumentCaptor<BrandedEmailRenderer.CallToAction> cta =
                ArgumentCaptor.forClass(BrandedEmailRenderer.CallToAction.class);
        verify(email, timeout(10_000).atLeastOnce())
                .sendEmail(eq(address), anyString(), anyString(), anyString(), cta.capture());
        List<String> tokens = new ArrayList<>();
        for (BrandedEmailRenderer.CallToAction c : cta.getAllValues()) {
            String url = c.url();
            tokens.add(url.substring(url.indexOf("#token=") + "#token=".length()));
        }
        return tokens;
    }

    /** The most recent invite token emailed to {@code address}. */
    protected String inviteTokenSentTo(String address) {
        List<String> tokens = inviteTokensSentTo(address);
        return tokens.get(tokens.size() - 1);
    }

    /** Waits for the {@code n}th invite email to {@code address} and returns its token. */
    protected String nthInviteTokenSentTo(String address, int n) {
        verify(email, timeout(10_000).times(n)).sendEmail(eq(address), anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.any(BrandedEmailRenderer.CallToAction.class));
        return inviteTokenSentTo(address);
    }

    protected ResultActions accept(String token, String password) throws Exception {
        return mockMvc.perform(post("/auth/staff-invite/accept").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "token", token, "newPassword", password, "confirmPassword", password))));
    }

    /** First sign-in of a staff account: password step → forced enrolment → enroll/complete. */
    protected JsonNode signInEnrolling(String address, String password) throws Exception {
        JsonNode step = data(mockMvc.perform(post("/auth/login").header("X-Device-Id", DEVICE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("identifier", address, "password", password))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mfaEnrollmentRequired").value(true))
                .andExpect(jsonPath("$.data.token").doesNotExist()));
        JsonNode start = data(mockMvc.perform(post("/auth/mfa/enroll/start").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("mfaToken", step.at("/mfaToken").asText()))))
                .andExpect(status().isOk()));
        return data(mockMvc.perform(post("/auth/mfa/enroll/complete").header("X-Device-Id", DEVICE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "mfaToken", start.at("/mfaToken").asText(),
                                "code", totp(start.at("/secret").asText())))))
                .andExpect(status().isOk()));
    }

    /** A JWT's claims, read the way a consumer sees them. */
    protected JsonNode claims(String jwt) throws Exception {
        String payload = jwt.split("\\.")[1];
        return objectMapper.readTree(new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8));
    }

    protected static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        if (array != null) array.forEach(n -> out.add(n.asText()));
        return out;
    }

    protected long count(String sql, Object... args) {
        var query = em.createNativeQuery(sql);
        for (int i = 0; i < args.length; i++) query.setParameter(i + 1, args[i]);
        return ((Number) query.getSingleResult()).longValue();
    }

    protected Object single(String sql, Object... args) {
        var query = em.createNativeQuery(sql);
        for (int i = 0; i < args.length; i++) query.setParameter(i + 1, args[i]);
        return query.getSingleResult();
    }
}
