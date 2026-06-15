package com.innbucks.userservice.cells;

import com.innbucks.userservice.util.MsisdnCountryResolver;
import com.innbucks.userservice.util.MsisdnMasking;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Verifies that an inbound request belongs to this cell — by the MSISDN
 * carried in the request body, by the {@code homeCountry} claim on a
 * presented JWT, or by the explicit country a caller asserts. Throws
 * {@link WrongCellException} on mismatch so {@code GlobalExceptionHandler}
 * can emit {@code 409 wrong_cell} with a redirect URL to the home cell
 * (when {@link CellRegistry} knows it).
 *
 * <p>Tolerates "no signal":
 * <ul>
 *   <li>Null/blank input → no-op (the existing validation will handle it).</li>
 *   <li>Unresolvable MSISDN prefix → no-op (a non-InnBucks-market number
 *       isn't a wrong-cell signal — let it fail downstream with the existing
 *       MSISDN validation, not with a misleading "wrong cell" redirect).</li>
 * </ul>
 *
 * <p>The deployment country comes from {@code innbucks.country}; same
 * value the rest of the stack reads.
 */
@Component
@Slf4j
public class CellAffinityChecker {

    private final String deploymentCountry;
    private final CellRegistry registry;

    public CellAffinityChecker(@Value("${innbucks.country:ZW}") String deploymentCountry,
                               CellRegistry registry) {
        this.deploymentCountry = deploymentCountry == null ? "" : deploymentCountry.trim().toUpperCase();
        this.registry = registry;
    }

    @PostConstruct
    void describe() {
        log.info("[cells] affinity checker active: this cell hosts country={} (known cells={})",
                deploymentCountry, registry.countries());
    }

    /**
     * Verify the MSISDN belongs to this cell. Use on entry points where the
     * body field is unambiguously a phone number ({@code /auth/otp/request},
     * {@code /register}, customer-tier registrations).
     */
    public void requireDomesticMsisdn(String msisdn) {
        verifyResolved(MsisdnCountryResolver.resolve(msisdn), "msisdn=" + MsisdnMasking.mask(msisdn));
    }

    /**
     * Verify a possibly-MSISDN identifier (e.g. {@code /auth/login}'s
     * {@code identifier}, which may be an email OR a phone). When the
     * input doesn't parse as a phone, no-op — we cannot route by email.
     */
    public void requireDomesticIfMsisdn(String identifier) {
        Optional<String> iso = MsisdnCountryResolver.resolve(identifier);
        if (iso.isPresent()) {
            verifyResolved(iso, "identifier=" + MsisdnMasking.mask(identifier));
        }
    }

    /**
     * Verify the explicit country (ISO 3166-1 alpha-2) belongs to this cell.
     * Use from {@code JwtFilter} after extracting the {@code homeCountry}
     * claim. A {@code null}/blank claim is permitted (legacy tokens minted
     * before the claim existed) — the JWT signature is still the auth.
     */
    public void requireDomesticCountry(String iso) {
        if (iso == null || iso.isBlank()) return;
        verifyResolved(Optional.of(iso), "homeCountry=" + iso);
    }

    private void verifyResolved(Optional<String> isoOpt, String contextForLog) {
        isoOpt.map(String::trim).map(String::toUpperCase)
                .filter(iso -> !iso.equalsIgnoreCase(deploymentCountry))
                .ifPresent(iso -> {
                    String homeUrl = registry.baseUrlFor(iso).orElse(null);
                    log.info("[cells] wrong-cell on {} ({}): home={} redirect={}",
                            deploymentCountry, contextForLog, iso, homeUrl == null ? "<unknown>" : homeUrl);
                    throw new WrongCellException(iso, homeUrl);
                });
    }

    public String deploymentCountry() {
        return deploymentCountry;
    }
}
