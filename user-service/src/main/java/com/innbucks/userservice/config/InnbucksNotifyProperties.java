package com.innbucks.userservice.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Config for the InnBucks public notification API (the same API Gateway the
 * payment rail authenticates to). Email is delivered via
 * {@code POST /api/notification/email} after a {@code POST /auth/third-party}
 * login. Reuses the platform's {@code BANK_API_*} credentials — see
 * application.yaml's {@code innbucks-notify} block.
 */
@Data
@ConfigurationProperties(prefix = "innbucks-notify")
public class InnbucksNotifyProperties {
    /** Gateway root, e.g. https://staging.innbucks.co.zw (no trailing path). */
    private String baseUrl;
    /** Sent as the X-Api-Key header on login + every call. */
    private String apiKey;
    /** Third-party client login username. */
    private String username;
    /** Third-party client login password. */
    private String password;
    private int connectTimeoutMs = 3000;
    private int readTimeoutMs = 20000;
    /** Fallback token lifetime when the JWT carries no parseable exp. */
    private Duration tokenTtl = Duration.ofMinutes(8);

    /**
     * When true (the default), outbound emails are sent as branded HTML in the
     * {@code message} field. Confirmed ON: the gateway renders the message field
     * as HTML — observed in prod when a plain-text body's newlines collapsed into
     * one run-on paragraph (HTML whitespace behaviour), which also proves the
     * gateway treats the message as HTML regardless of any tags. Set to false
     * only as a rollback to the plain-text-with-footer path (which, since the
     * gateway renders HTML, renders as one run-on block — kept solely as a
     * fail-safe).
     */
    private boolean htmlEnabled = true;

    /**
     * Public URL of the InnBucks logo shown in the HTML email header. Email
     * clients block {@code data:} URIs in {@code <img>}, so this must be a hosted
     * image (e.g. https://www.innbucks.co.zw/…/logo.png). When blank, the HTML
     * template falls back to a CSS-drawn roundel + wordmark so the email still
     * renders branded without a hosted asset.
     */
    private String logoUrl = "";
}
