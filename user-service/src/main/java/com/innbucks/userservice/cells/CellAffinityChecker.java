package com.innbucks.userservice.cells;

import com.innbucks.userservice.util.MsisdnCountryResolver;
import com.innbucks.userservice.util.MsisdnMasking;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

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
        verify(MsisdnCountryResolver.resolve(msisdn).orElse(null), "msisdn=" + MsisdnMasking.mask(msisdn));
    }

    /**
     * Verify a possibly-MSISDN identifier (e.g. {@code /auth/login}'s
     * {@code identifier}, which may be an email OR a phone). An email resolves
     * to no country → no-op (we cannot route by email); only a phone carries a
     * routing signal.
     */
    public void requireDomesticIfMsisdn(String identifier) {
        verify(MsisdnCountryResolver.resolve(identifier).orElse(null),
                "identifier=" + MsisdnMasking.mask(identifier));
    }

    /**
     * Verify the explicit country (ISO 3166-1 alpha-2) belongs to this cell.
     * Use from {@code JwtFilter} after extracting the {@code homeCountry}
     * claim. A {@code null}/blank claim is permitted (legacy tokens minted
     * before the claim existed) — the JWT signature is still the auth.
     */
    public void requireDomesticCountry(String iso) {
        verify(iso, "homeCountry=" + (iso == null ? "" : iso));
    }

    /**
     * Throws {@link WrongCellException} when {@code iso} is non-blank and
     * resolves to a country other than this deployment's. A {@code null}/blank
     * iso is a no-op — an unresolvable MSISDN or a legacy token with no
     * {@code homeCountry} claim is not a wrong-cell signal.
     *
     * <p>Takes a plain nullable {@code String}, not an {@link java.util.Optional}
     * — Optional is a return type, not a parameter type.
     */
    private void verify(String iso, String contextForLog) {
        if (iso == null || iso.isBlank()) return;
        String normalised = iso.trim().toUpperCase();
        if (normalised.equalsIgnoreCase(deploymentCountry)) return;
        String homeUrl = registry.baseUrlFor(normalised).orElse(null);
        log.info("[cells] wrong-cell on {} ({}): home={} redirect={}",
                deploymentCountry, contextForLog, normalised, homeUrl == null ? "<unknown>" : homeUrl);
        throw new WrongCellException(normalised, homeUrl);
    }

    public String deploymentCountry() {
        return deploymentCountry;
    }
}
