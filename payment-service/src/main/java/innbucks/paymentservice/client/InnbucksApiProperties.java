package innbucks.paymentservice.client;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Config for the public InnBucks Merchant API ({@code staging.innbucks.co.zw}
 * in staging) — the 2D-code payment rail behind {@code POST /payments}.
 *
 * <p>Auth model: every call carries {@code X-Api-Key}; business calls also
 * carry a Bearer token obtained from {@code POST /auth/third-party} with the
 * client {@code username}/{@code password}. The token is cached and refreshed
 * on expiry or 401 (see {@link InnbucksApiClient}).
 *
 * <p>All three credentials are secrets — env-only, never committed. The
 * client refuses business calls when any of them is blank. The backing env
 * vars keep their original {@code BANK_API_*} names (same platform, same
 * credentials, already in deployment runbooks); the credentials must belong
 * to a MERCHANT-type API client allowed to generate PAYMENT codes.
 */
@Data
@ConfigurationProperties(prefix = "innbucks-api")
public class InnbucksApiProperties {
    private String baseUrl;
    private String apiKey;
    private String username;
    private String password;
    private int connectTimeoutMs = 3000;
    private int readTimeoutMs = 20000;
    /**
     * Fallback token lifetime when the JWT's {@code exp} claim can't be
     * parsed. The client refreshes 30s before whichever expiry it derives.
     */
    private Duration tokenTtl = Duration.ofMinutes(8);
}
