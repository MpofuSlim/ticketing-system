package com.innbucks.userservice.cells;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Per-cell registry of {ISO country code → public base URL} parsed from the
 * {@code INNBUCKS_CELLS_REGISTRY} env var (JSON object). Used by
 * {@link CellAffinityChecker} to redirect wrong-cell requests and by
 * {@code CellLookupController} to answer the mobile app's "which cell hosts
 * this MSISDN" question at signup/login.
 *
 * <p>Source of truth: each cell holds its own copy of the registry as an env
 * var, kept consistent at deploy time (see {@code deploy/cells/cell.example.env}).
 * A central directory would be a SPOF — at this fleet size, syncing N env
 * vars when a market launches is the right trade.
 *
 * <p>Empty / missing registry is legal — single-cell deployments before
 * step 7 lights up. The lookup endpoint then returns "no cell" for any
 * MSISDN, and {@link CellAffinityChecker} can still surface the home
 * country (it just can't include a redirect URL).
 *
 * <p>Keys are normalised to upper-case ISO 3166-1 alpha-2; lookups are
 * case-insensitive. Unknown ISO → {@link Optional#empty()}.
 */
@Component
@Slf4j
public class CellRegistry {

    private final String rawJson;
    private final ObjectMapper mapper;
    private Map<String, String> cells = Collections.emptyMap();

    public CellRegistry(@Value("${innbucks.cells.registry:{}}") String rawJson,
                        ObjectMapper mapper) {
        this.rawJson = rawJson == null ? "{}" : rawJson.trim();
        this.mapper = mapper;
    }

    @PostConstruct
    void parse() {
        if (rawJson.isEmpty() || "{}".equals(rawJson)) {
            log.warn("[cells] INNBUCKS_CELLS_REGISTRY is empty — cross-cell redirects will surface 'wrong_cell' WITHOUT a homeBaseUrl. Set it once a 2nd cell is live (see deploy/cells/cell.example.env).");
            cells = Collections.emptyMap();
            return;
        }
        try {
            Map<String, String> parsed = mapper.readValue(rawJson, new TypeReference<>() {});
            Map<String, String> normalised = new LinkedHashMap<>();
            parsed.forEach((iso, url) -> {
                if (iso == null || url == null || iso.isBlank() || url.isBlank()) return;
                normalised.put(iso.trim().toUpperCase(), url.trim());
            });
            this.cells = Collections.unmodifiableMap(normalised);
            log.info("[cells] registry loaded: {} cell(s) — {}", cells.size(), cells.keySet());
        } catch (Exception ex) {
            // Fail closed: an unparseable registry means we can't safely
            // redirect anyone, but it must not crash the app. Log loudly.
            log.error("[cells] INNBUCKS_CELLS_REGISTRY is not valid JSON ({}) — running with an EMPTY registry; redirects will lack a homeBaseUrl until fixed. Raw: {}",
                    ex.getMessage(), rawJson);
            cells = Collections.emptyMap();
        }
    }

    public Optional<String> baseUrlFor(String iso) {
        if (iso == null || iso.isBlank()) return Optional.empty();
        return Optional.ofNullable(cells.get(iso.trim().toUpperCase()));
    }

    public Set<String> countries() {
        return cells.keySet();
    }
}
