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
     * When true, outbound emails are sent as branded HTML in the {@code message}
     * field instead of plain text. OFF by default: it is unconfirmed whether the
     * notification gateway renders HTML in {@code message} (the payment rail's
     * confirmation emails are HTML, but through the banking platform's own
     * templates). Flip on in staging, send yourself one email, and check whether
     * it renders as a formatted email or shows raw tags — if it renders, this is
     * safe to enable in prod; if it shows tags, leave it off (plain text with the
     * standard footer is the fallback) and the gateway needs a different HTML path.
     */
    private boolean htmlEnabled = false;

    /**
     * Public URL of the InnBucks logo shown in the HTML email header. Email
     * clients block {@code data:} URIs in {@code <img>}, so this must be a hosted
     * image (e.g. https://www.innbucks.co.zw/…/logo.png). When blank, the HTML
     * template falls back to a CSS-drawn roundel + wordmark so the email still
     * renders branded without a hosted asset.
     */
    private String logoUrl = "";
}
