package com.innbucks.userservice.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;

/**
 * Step 2 of the multi-cell deployment roadmap. Pins the running JVM to a
 * single InnBucks country market and stamps that country into MDC on every
 * inbound request so every log line — authenticated or not — carries a
 * cell-level country tag.
 *
 * <p>Two halves:
 * <ol>
 *   <li><b>Startup guard:</b> reads {@code innbucks.country}
 *       (env {@code INNBUCKS_COUNTRY}; defaults to {@code ZW} for the
 *       current single-cell deployment). Rejects unknown values at bean
 *       construction so a typo like {@code Zimbabwe} or {@code ZN} fails
 *       fast instead of silently mis-tagging every log line.</li>
 *   <li><b>MDC filter:</b> highest-precedence {@link OncePerRequestFilter}
 *       puts {@link #MDC_KEY} into MDC at request entry, clears in a
 *       {@code finally} so thread-pool threads don't leak the value
 *       across requests. Mirrors {@link CorrelationIdFilter}'s shape
 *       exactly — same registration pattern, same precedence band — so
 *       both keys are guaranteed present by the time downstream filters
 *       (JwtFilter, controllers, exception advice) run.</li>
 * </ol>
 *
 * <p>Pairs with the {@code homeCountry} MDC key set by
 * {@link com.innbucks.userservice.security.JwtFilter}: this filter sets
 * the deployment country ({@code country}); JwtFilter sets the customer's
 * country from their JWT claim ({@code homeCountry}). On a single global
 * cell they're usually the same; once cell #2 lands and the edge routes
 * by claim, a mismatch is a wrong-cell signal worth alerting on.
 *
 * <p>Default {@code ZW} is intentional — current deployment is the
 * Zimbabwe home market, and a default keeps local dev / tests booting
 * without env wiring. Cell #2 onwards MUST set the env var explicitly.
 */
@Configuration
@Slf4j
public class CountryMdcConfig {

    public static final String MDC_KEY = "country";

    /**
     * The ten InnBucks target markets as ISO 3166-1 alpha-2 codes — same
     * alphabet as {@link com.innbucks.userservice.util.MsisdnCountryResolver}.
     * Kept as a literal set rather than reusing the resolver's keys so this
     * file is self-contained: a future SPI split between routing-keys and
     * deployment-pins won't silently change behaviour.
     */
    private static final Set<String> KNOWN_COUNTRIES = Set.of(
            "ZW", "KE", "ZM", "MW", "ZA", "BW", "MZ", "LS", "SZ", "NG"
    );

    private final String country;

    public CountryMdcConfig(@Value("${innbucks.country:ZW}") String configured) {
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException(
                    "innbucks.country (env INNBUCKS_COUNTRY) must be set to one of " + KNOWN_COUNTRIES
                            + " — refusing to start without a country pin");
        }
        String normalised = configured.trim().toUpperCase(Locale.ROOT);
        if (!KNOWN_COUNTRIES.contains(normalised)) {
            throw new IllegalStateException(
                    "innbucks.country='" + configured + "' is not a known InnBucks market. Allowed: "
                            + KNOWN_COUNTRIES + " — refusing to start with an unknown country pin");
        }
        this.country = normalised;
        log.info("[startup] user-service pinned to country={}", normalised);
    }

    String getCountry() {
        return country;
    }

    @Bean
    public FilterRegistrationBean<OncePerRequestFilter> countryMdcFilterRegistration() {
        FilterRegistrationBean<OncePerRequestFilter> reg = new FilterRegistrationBean<>();
        reg.setFilter(new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request,
                                            HttpServletResponse response,
                                            FilterChain chain)
                    throws ServletException, IOException {
                MDC.put(MDC_KEY, country);
                try {
                    chain.doFilter(request, response);
                } finally {
                    // Servlet container reuses request threads. Without
                    // this remove, the next request on the same thread
                    // would inherit our country tag if its own filter
                    // chain somehow skipped this one (defence in depth).
                    MDC.remove(MDC_KEY);
                }
            }
        });
        // Same precedence band as CorrelationIdFilter — both should be in
        // place by the time any other filter (security, JWT, idempotency)
        // logs anything.
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
        reg.addUrlPatterns("/*");
        return reg;
    }
}
