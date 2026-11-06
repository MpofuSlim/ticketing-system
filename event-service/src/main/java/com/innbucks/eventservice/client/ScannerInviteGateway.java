package com.innbucks.eventservice.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Asks user-service to tell a person an organizer added them as gate staff
 * (V20), or as a sales agent (V21): {@code POST /users/internal/scanner-invites}
 * with the shared {@code X-Internal-Token}. user-service owns the wording and
 * the channels (WhatsApp, SMS when WhatsApp fails); this side names the number,
 * the organizer, the event and the roles ({@code SCAN}, {@code SELL}),
 * nothing else. user-service picks one of its fixed sentences from the roles,
 * so no caller text reaches a stranger.
 *
 * <p>Best-effort, like {@link StaffNotificationGateway}: a failure is logged
 * and never fails the assignment it accompanies. Called after the assignment
 * has committed, so a rolled-back one messages nobody.
 */
@Component
@Slf4j
public class ScannerInviteGateway {

    private final RestTemplate restTemplate;
    private final String userServiceBaseUrl;
    private final String internalToken;

    public ScannerInviteGateway(
            RestTemplate restTemplate,
            @Value("${user-service.base-url:http://user-service}") String userServiceBaseUrl,
            @Value("${innbucks.internal-api-token:}") String internalToken) {
        this.restTemplate = restTemplate;
        this.userServiceBaseUrl = userServiceBaseUrl;
        this.internalToken = internalToken;
    }

    /**
     * @param roles what the row does at send time, {@code ["SCAN"]},
     *              {@code ["SELL"]} or both; null or empty is left out
     *              (user-service then reads it as {@code ["SCAN"]})
     */
    public void sendInvite(UUID scannerId, String phoneNumber, String organizerName, String eventTitle,
                           List<String> roles) {
        if (phoneNumber == null || phoneNumber.isBlank()) {
            return;
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Internal-Token", internalToken);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("phoneNumber", phoneNumber);
            if (organizerName != null && !organizerName.isBlank()) {
                body.put("organizerName", organizerName);
            }
            if (eventTitle != null && !eventTitle.isBlank()) {
                body.put("eventTitle", eventTitle);
            }
            if (scannerId != null) {
                body.put("scannerId", scannerId.toString());
            }
            if (roles != null && !roles.isEmpty()) {
                body.put("roles", List.copyOf(roles));
            }

            restTemplate.exchange(userServiceBaseUrl + "/users/internal/scanner-invites",
                    HttpMethod.POST, new HttpEntity<>(body, headers), Void.class);
        } catch (RuntimeException e) {
            log.warn("Scanner invite message not requested scannerId={} cause={}", scannerId, e.toString());
        }
    }
}
