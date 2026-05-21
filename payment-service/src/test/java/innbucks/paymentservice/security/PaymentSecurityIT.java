package innbucks.paymentservice.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import innbucks.paymentservice.dto.PaymentMethod;
import innbucks.paymentservice.dto.ShopCheckoutRequest;
import innbucks.paymentservice.testsupport.PostgresIntegrationTestBase;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import javax.crypto.SecretKey;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins the SecurityConfig + JwtFilter wiring. Before this filter chain
 * landed, /payments/** and /actuator/prometheus were anonymously
 * reachable; shop-checkout took msisdn from the request body and would
 * burn any customer's points by phone number. The cases below assert
 * the new floor:
 *
 * <ul>
 *   <li>/actuator/health stays anonymous (orchestrator probe path).</li>
 *   <li>/actuator/prometheus now requires a valid JWT (returns 401 without one).</li>
 *   <li>POST /payments/shop-checkout without a JWT returns 401.</li>
 *   <li>With a valid JWT, the request reaches the controller — the body-supplied
 *       msisdn is dropped and the principal's MSISDN is used instead. We don't
 *       assert success here (downstream loyalty-service is unreachable in this
 *       IT) — only that the filter chain doesn't 401 the call.</li>
 * </ul>
 */
class PaymentSecurityIT extends PostgresIntegrationTestBase {

    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper objectMapper;

    @Value("${jwt.secret}")
    private String jwtSecret;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void actuatorHealth_isAnonymouslyReachable() throws Exception {
        // The endpoint must accept the call without auth — status 200 (all
        // downstreams healthy) or 503 (Spring health aggregator reports DOWN
        // because the IT config points loyalty/oradian/booking at
        // *.invalid hostnames) are both valid here. The contract under
        // test is "the security filter let this through", and that's
        // proven by anything NOT 401.
        mockMvc.perform(get("/actuator/health"))
                .andExpect(result -> {
                    int s = result.getResponse().getStatus();
                    if (s == 401) {
                        throw new AssertionError(
                                "Expected /actuator/health to be anonymously reachable, got 401");
                    }
                });
    }

    @Test
    void actuatorPrometheus_requiresJwt() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("401 UNAUTHORIZED"));
    }

    @Test
    void shopCheckout_withoutJwt_returns401() throws Exception {
        ShopCheckoutRequest body = new ShopCheckoutRequest();
        body.setShopId(UUID.randomUUID());
        body.setMsisdn("0712345678");
        body.setPaymentMethod(PaymentMethod.CASH);
        body.setCashAmount(new BigDecimal("10.00"));

        mockMvc.perform(post("/payments/shop-checkout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shopCheckout_withTamperedJwt_returns401() throws Exception {
        ShopCheckoutRequest body = new ShopCheckoutRequest();
        body.setShopId(UUID.randomUUID());
        body.setMsisdn("0712345678");
        body.setPaymentMethod(PaymentMethod.CASH);
        body.setCashAmount(new BigDecimal("10.00"));

        // Mint with the wrong key → signature won't verify.
        SecretKey wrongKey = Keys.hmacShaKeyFor("not-the-right-secret-not-the-right-secret".getBytes(StandardCharsets.UTF_8));
        String token = Jwts.builder()
                .claims(Map.of("phoneNumber", "0712345678"))
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(wrongKey)
                .compact();

        mockMvc.perform(post("/payments/shop-checkout")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
    }

    @Test
    void shopCheckout_withValidJwtButNoPhoneClaim_returns401() throws Exception {
        ShopCheckoutRequest body = new ShopCheckoutRequest();
        body.setShopId(UUID.randomUUID());
        body.setPaymentMethod(PaymentMethod.CASH);
        body.setCashAmount(new BigDecimal("10.00"));

        // Signature-valid but no phoneNumber claim — staff token shape.
        SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        String token = Jwts.builder()
                .claims(Map.of("roles", java.util.List.of("MERCHANT_ADMIN")))
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(key)
                .compact();

        mockMvc.perform(post("/payments/shop-checkout")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
    }

    @Test
    void shopCheckout_withValidJwt_passesAuthAndReachesController() throws Exception {
        // Body says one MSISDN; token claims another. The controller MUST
        // use the token value (the body-supplied one is the attack vector
        // this PR closes). Loyalty downstream is unreachable in IT — we
        // expect a 5xx / 503-class status, NOT a 401. Anything but 401
        // proves auth passed.
        ShopCheckoutRequest body = new ShopCheckoutRequest();
        body.setShopId(UUID.randomUUID());
        body.setMsisdn("9999999999"); // attacker tries to redirect to someone else
        body.setPaymentMethod(PaymentMethod.CASH);
        body.setCashAmount(new BigDecimal("10.00"));

        SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        String token = Jwts.builder()
                .claims(Map.of("phoneNumber", "0712345678"))
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(key)
                .compact();

        mockMvc.perform(post("/payments/shop-checkout")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                // Loyalty mock isn't wired in this IT, so the controller's
                // call to loyaltyServiceClient.shopCheckout throws or returns
                // a non-2xx. Either way, status is NOT 401 — that's the only
                // assertion that matters for the security contract.
                .andExpect(result -> {
                    int s = result.getResponse().getStatus();
                    if (s == 401) {
                        throw new AssertionError("Expected auth to pass (any non-401 status), got 401");
                    }
                });
    }
}
