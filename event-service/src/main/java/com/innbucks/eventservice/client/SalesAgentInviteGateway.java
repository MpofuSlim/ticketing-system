package com.innbucks.eventservice.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A sales agent's Foundry portal invite (V21), through user-service, which owns
 * the invite, the account and the password: {@code POST
 * /users/internal/sales-agent-invites} and {@code DELETE
 * /users/internal/sales-agent-invites/{teamMemberId}}, each with the shared
 * {@code X-Internal-Token}.
 *
 * <p>Unlike {@link ScannerInviteGateway}, the invite is NOT best-effort: the
 * organizer asked for it and must hear whether it went. So it answers with an
 * {@link Outcome} instead of swallowing: {@link Sent}, {@link Refused} (a 400
 * from user-service, with its own {@code errorCode} and message, e.g.
 * {@code invalid_email}, {@code email_domain_reserved}) or {@link Unavailable}
 * (anything else: unreachable, a timeout, a 5xx, a 401 from a wrong token, a
 * 404 from a user-service without the endpoint). Never retried: the call sends
 * an email, and the pool never re-sends a request.
 *
 * <p>The revoke IS best-effort: it runs after the portal access was cleared
 * here, and a failure is logged.
 *
 * <p>Never logs the email address or the phone number.
 */
@Component
@Slf4j
public class SalesAgentInviteGateway {

    static final String PATH = "/users/internal/sales-agent-invites";

    /** What a refusal's errorCode may look like before it is passed to a client. */
    private static final Pattern ERROR_CODE = Pattern.compile("[a-z][a-z0-9_]{0,63}");
    private static final Pattern FIELD = Pattern.compile("[A-Za-z][A-Za-z0-9_.]{0,63}");
    private static final int MAX_MESSAGE = 300;
    static final String FALLBACK_ERROR_CODE = "portal_invite_refused";
    static final String FALLBACK_MESSAGE = "The portal invite could not be sent. Check the email address.";

    private static final ObjectMapper JSON = new ObjectMapper();

    /** What the invite came to. */
    public sealed interface Outcome permits Sent, Refused, Unavailable { }

    /** user-service sent it; {@code expiresAt} as it said, or null. */
    public record Sent(String expiresAt) implements Outcome { }

    /** user-service refused it (400): its code and words for the organizer, and the field it named, if any. */
    public record Refused(String errorCode, String message, String field) implements Outcome { }

    /** No answer that decides anything; {@code reason} is for the log only. */
    public record Unavailable(String reason) implements Outcome { }

    private final RestTemplate restTemplate;
    private final String userServiceBaseUrl;
    private final String internalToken;

    public SalesAgentInviteGateway(
            RestTemplate restTemplate,
            @Value("${user-service.base-url:http://user-service}") String userServiceBaseUrl,
            @Value("${innbucks.internal-api-token:}") String internalToken) {
        this.restTemplate = restTemplate;
        this.userServiceBaseUrl = userServiceBaseUrl;
        this.internalToken = internalToken;
    }

    public Outcome invite(UUID teamMemberId, UUID organizerUuid, String organizerName,
                          String phoneNumber, String email) {
        if (email == null || email.isBlank()) {
            return new Refused("invalid_email", "Enter the agent's email address.", "email");
        }
        if (teamMemberId == null || organizerUuid == null || phoneNumber == null || phoneNumber.isBlank()) {
            return new Unavailable("incomplete_request");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("teamMemberId", teamMemberId.toString());
        body.put("organizerUuid", organizerUuid.toString());
        if (organizerName != null && !organizerName.isBlank()) {
            body.put("organizerName", organizerName);
        }
        body.put("phoneNumber", phoneNumber);
        body.put("email", email);
        try {
            ResponseEntity<String> response = restTemplate.exchange(userServiceBaseUrl + PATH, HttpMethod.POST,
                    new HttpEntity<>(body, headers(true)), String.class);
            log.info("Portal invite sent teamMemberId={} status={}", teamMemberId, response.getStatusCode().value());
            return new Sent(text(read(response.getBody()).path("data").path("expiresAt")));
        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode().value() == HttpStatus.BAD_REQUEST.value()) {
                JsonNode refusal = read(e.getResponseBodyAsString());
                String code = text(refusal.path("data").path("errorCode"));
                String message = text(refusal.path("message"));
                String field = text(refusal.path("data").path("field"));
                log.info("Portal invite refused teamMemberId={} errorCode={}", teamMemberId, code);
                return new Refused(
                        code != null && ERROR_CODE.matcher(code).matches() ? code : FALLBACK_ERROR_CODE,
                        message != null && !message.isBlank() && message.length() <= MAX_MESSAGE
                                ? message : FALLBACK_MESSAGE,
                        field != null && FIELD.matcher(field).matches() ? field : null);
            }
            log.warn("Portal invite not sent teamMemberId={} status={} errorCode={}", teamMemberId,
                    e.getStatusCode().value(), text(read(e.getResponseBodyAsString()).path("data").path("errorCode")));
            return new Unavailable("http_" + e.getStatusCode().value());
        } catch (RuntimeException e) {
            log.warn("Portal invite not sent teamMemberId={} cause={}", teamMemberId, e.toString());
            return new Unavailable("unreachable");
        }
    }

    /** Revokes the team member's open portal invites; true when user-service answered 2xx. Never throws. */
    public boolean revoke(UUID teamMemberId) {
        if (teamMemberId == null) {
            return false;
        }
        try {
            restTemplate.exchange(userServiceBaseUrl + PATH + "/" + teamMemberId, HttpMethod.DELETE,
                    new HttpEntity<>(headers(false)), String.class);
            log.info("Portal invites revoked teamMemberId={}", teamMemberId);
            return true;
        } catch (RuntimeException e) {
            log.warn("Portal invite revoke failed teamMemberId={} cause={}", teamMemberId, e.toString());
            return false;
        }
    }

    private HttpHeaders headers(boolean withBody) {
        HttpHeaders headers = new HttpHeaders();
        if (withBody) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        headers.set("X-Internal-Token", internalToken);
        return headers;
    }

    private static JsonNode read(String body) {
        if (body == null || body.isBlank()) {
            return JSON.missingNode();
        }
        try {
            return JSON.readTree(body);
        } catch (Exception e) {
            return JSON.missingNode();
        }
    }

    private static String text(JsonNode node) {
        return node == null || !node.isTextual() ? null : node.asText();
    }
}
