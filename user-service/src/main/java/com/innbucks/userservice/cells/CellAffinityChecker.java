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
    /** Countries this cell takes in as its own: see {@link #adopts}. Empty by default. */
    private final java.util.Set<String> adoptedCountries;

    public CellAffinityChecker(String deploymentCountry, CellRegistry registry) {
        this(deploymentCountry, registry, "");
    }

    @org.springframework.beans.factory.annotation.Autowired
    public CellAffinityChecker(@Value("${innbucks.country:ZW}") String deploymentCountry,
                               CellRegistry registry,
                               @Value("${innbucks.cells.adopted-countries:}") String adoptedCountries) {
        this.deploymentCountry = deploymentCountry == null ? "" : deploymentCountry.trim().toUpperCase();
        this.registry = registry;
        java.util.Set<String> adopted = new java.util.TreeSet<>();
        if (adoptedCountries != null) {
            for (String iso : adoptedCountries.split(",")) {
                String code = iso.trim().toUpperCase(java.util.Locale.ROOT);
                if (code.length() == 2 && !code.equals(this.deploymentCountry)) adopted.add(code);
            }
        }
        this.adoptedCountries = java.util.Collections.unmodifiableSet(adopted);
    }

    @PostConstruct
    void describe() {
        log.info("[cells] affinity checker active: this cell hosts country={} (known cells={})",
                deploymentCountry, registry.countries());
        for (String iso : adoptedCountries) {
            if (registry.baseUrlFor(iso).isPresent()) {
                log.warn("[cells] {} is listed in CELLS_ADOPTED_COUNTRIES but has a cell of its own: "
                        + "it is NOT adopted here; its numbers keep being sent to that cell", iso);
            } else {
                log.warn("[cells] adopting {}: numbers from {} are accepted here and their accounts are "
                        + "homed in {} (CELLS_ADOPTED_COUNTRIES)", iso, iso, deploymentCountry);
            }
        }
    }

    /**
     * Whether this cell takes a country's numbers in as its own: the country is
     * on {@code innbucks.cells.adopted-countries} ({@code CELLS_ADOPTED_COUNTRIES},
     * empty by default) AND no cell of its own is registered. A Zimbabwean on a
     * South African line, or a visitor using the Zim super app, is then served
     * here instead of being sent to a ZA cell that does not exist. Once a ZA
     * cell is registered the listing stops applying on its own, so the switch
     * can never send an account away from the cell that really holds it.
     */
    public boolean adopts(String iso) {
        if (iso == null || iso.isBlank()) return false;
        String code = iso.trim().toUpperCase(java.util.Locale.ROOT);
        return adoptedCountries.contains(code) && registry.baseUrlFor(code).isEmpty();
    }

    /**
     * The home cell to store on a NEW account created here for {@code msisdn}:
     * the number's country, unless that country is unknown or adopted (see
     * {@link #adopts}), in which case this cell's. With the adopted list empty
     * this is exactly the prefix rule every account creator used before.
     */
    public String homeCountryFor(String msisdn) {
        String resolved = MsisdnCountryResolver.resolve(msisdn).orElse(null);
        if (resolved == null || adopts(resolved)) return deploymentCountry;
        return resolved;
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
        if (adopts(normalised)) return;
        String homeUrl = registry.baseUrlFor(normalised).orElse(null);
        log.info("[cells] wrong-cell on {} ({}): home={} redirect={}",
                deploymentCountry, contextForLog, normalised, homeUrl == null ? "<unknown>" : homeUrl);
        throw new WrongCellException(normalised, homeUrl);
    }

    public String deploymentCountry() {
        return deploymentCountry;
    }
}
