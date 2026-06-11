package innbucks.paymentservice.client;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Config for the public InnBucks Bank API ({@code staging.innbucks.co.zw} in
 * staging) — the direct integration that replaced the innbucks-core-gateway
 * s2s hop for ticket payments.
 *
 * <p>Auth model: every call carries {@code X-Api-Key}; business calls also
 * carry a Bearer token obtained from {@code POST /auth/third-party} with the
 * client {@code username}/{@code password}. The token is cached and refreshed
 * on expiry or 401 (see {@link BankApiClient}).
 *
 * <p>All three credentials are secrets — env-only, never committed. The
 * client refuses business calls when any of them is blank.
 */
@Data
@ConfigurationProperties(prefix = "bank-api")
public class BankApiProperties {
    private String baseUrl;
    private String apiKey;
    private String username;
    private String password;
    /**
     * Transaction {@code type} sent on {@code POST /bank/api/payment}. The
     * Postman collection's example uses {@code CARD_ON_US}; confirm the
     * correct value for wallet→merchant ticket payments with the InnBucks
     * team and override via {@code BANK_API_PAYMENT_TYPE} if it differs.
     */
    private String paymentType = "CARD_ON_US";
    private int connectTimeoutMs = 3000;
    private int readTimeoutMs = 20000;
    /**
     * Fallback token lifetime when the JWT's {@code exp} claim can't be
     * parsed. The client refreshes 30s before whichever expiry it derives.
     */
    private Duration tokenTtl = Duration.ofMinutes(8);
}
