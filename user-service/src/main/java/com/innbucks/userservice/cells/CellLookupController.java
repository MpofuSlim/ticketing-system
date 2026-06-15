package com.innbucks.userservice.cells;

import com.innbucks.userservice.dto.ApiResult;
import com.innbucks.userservice.util.MsisdnCountryResolver;
import com.innbucks.userservice.util.MsisdnMasking;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public lookup endpoint the mobile app calls once at signup / first login
 * to learn which cell hosts a given customer. Returns the ISO country and
 * (when the registry knows it) the home cell's public base URL.
 *
 * <p>Single-call workflow: client posts MSISDN → client persists
 * {@code baseUrl} → all subsequent requests go straight to the right cell.
 * The MSISDN+JWT affinity checks in this stack are the safety net for
 * misrouted calls, not the primary routing mechanism.
 */
@RestController
@RequestMapping("/cells")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Cells", description = "Per-cell edge routing — discover the home cell for a given customer.")
@SecurityRequirements
public class CellLookupController {

    private final CellRegistry registry;

    @GetMapping("/lookup")
    @Operation(
            summary = "Resolve the home cell for an MSISDN",
            description = "Public, unauthenticated. Returns the ISO 3166-1 alpha-2 country code "
                    + "derived from the MSISDN's dialling prefix, plus the home cell's public base URL "
                    + "when the registry contains one. The mobile app calls this once (at signup or "
                    + "first login) and persists `baseUrl` so subsequent requests go directly to the "
                    + "right cell.\n\n"
                    + "Returns 200 in both 'cell known' and 'country known but no cell deployed' cases "
                    + "— the absence of `baseUrl` is itself a meaningful answer (\"your market isn't "
                    + "live yet\"). 404 only when the MSISDN's prefix isn't in any InnBucks market.")
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "Country resolved; baseUrl present if a cell is deployed there",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = {
                                    @ExampleObject(name = "Cell known", value = """
                                            {
                                              "code": "200 OK",
                                              "message": "Home cell resolved",
                                              "data": {
                                                "homeCountry": "ZW",
                                                "homeBaseUrl": "https://api-zw.innbucks.com"
                                              }
                                            }
                                            """),
                                    @ExampleObject(name = "Country known, no cell yet", value = """
                                            {
                                              "code": "200 OK",
                                              "message": "Home country resolved (no cell deployed yet)",
                                              "data": {
                                                "homeCountry": "KE",
                                                "homeBaseUrl": null
                                              }
                                            }
                                            """)
                            })),
            @ApiResponse(responseCode = "404",
                    description = "MSISDN prefix is not in any InnBucks market",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "404 NOT_FOUND",
                                      "message": "MSISDN prefix is not in any InnBucks market",
                                      "data": null
                                    }
                                    """)))
    })
    public ResponseEntity<ApiResult<HomeCellLookup>> lookup(
            @RequestParam("msisdn")
            @Schema(description = "E.164 MSISDN (with or without leading '+').",
                    example = "+254712345678") String msisdn) {

        return MsisdnCountryResolver.resolve(msisdn)
                .map(String::toUpperCase)
                .map(iso -> {
                    String url = registry.baseUrlFor(iso).orElse(null);
                    HomeCellLookup payload = new HomeCellLookup(iso, url);
                    String msg = url == null
                            ? "Home country resolved (no cell deployed yet)"
                            : "Home cell resolved";
                    log.debug("[cells] lookup msisdn={} -> {} url={}",
                            MsisdnMasking.mask(msisdn), iso, url);
                    return ResponseEntity.ok(ApiResult.ok(msg, payload));
                })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResult.error(HttpStatus.NOT_FOUND,
                                "MSISDN prefix is not in any InnBucks market")));
    }

    @Schema(description = "Home-cell pointer for a given customer's MSISDN.")
    public record HomeCellLookup(
            @Schema(description = "ISO 3166-1 alpha-2 of the customer's home country.", example = "ZW")
            String homeCountry,
            @Schema(description = "Public base URL of the home cell — null when no cell is deployed for that country yet.",
                    example = "https://api-zw.innbucks.com", nullable = true)
            String homeBaseUrl) {}
}
