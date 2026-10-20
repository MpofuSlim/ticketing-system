package com.innbucks.userservice.controller;

import com.innbucks.userservice.dto.ApiResult;
import com.innbucks.userservice.dto.PosApiKeyDTOs;
import com.innbucks.userservice.service.PosApiKeyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;

/**
 * {@code POST /auth/pos/token}: a merchant's point-of-sale backend swaps its
 * POS API key for a 15-minute cashier token, with no password and no second
 * factor. Under {@code /auth}, so it is {@code permitAll} and skipped by
 * {@code JwtFilter}; the key is the credential and is checked in the service.
 * Reached through the gateway's IP-keyed, fail-safe {@code auth-pos-token-route}.
 */
@RestController
@RequestMapping("/auth/pos")
@RequiredArgsConstructor
@Tag(name = "POS integration", description = "Machine sign-in for a merchant's point-of-sale system")
public class PosTokenController {

    static final String API_KEY_HEADER = "X-Api-Key";
    private static final String CLIENT_IP_HEADER = "X-Real-IP";

    private final PosApiKeyService posApiKeys;

    @PostMapping("/token")
    @Operation(summary = "Swap a POS API key for a cashier access token",
            description = """
                    Server-to-server only: the POS backend sends its key in the `X-Api-Key` header and gets a
                    bearer token for the loyalty till endpoints (earn, burn, voucher redeem, the till checkout,
                    QR status) at the key's merchant. No password, no 2FA, no refresh token: ask for a new
                    token before `expiresIn` runs out.

                    The body is optional. A **merchant-wide** key acts for one shop when it names `shopId`
                    (the shop must belong to the key's merchant); without it the token is merchant-wide and
                    carries no shop. A **shop-pinned** key always acts for its shop.

                    An unknown key, a revoked key and a call from an address outside the key's allow-list
                    are the same `401 pos_key_rejected`. Revoking a key also stops its live tokens at once.
                    Never call this from a browser or a till: the key belongs on a server.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Token issued",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Issued",
                            value = """
                                    {
                                      "code": "200 OK",
                                      "message": "POS token issued",
                                      "data": {
                                        "token": "eyJhbGciOiJIUzI1NiJ9.eyJyb2xlcyI6WyJTSE9QX1VTRVIiXX0.sig",
                                        "tokenType": "Bearer",
                                        "expiresIn": 900,
                                        "merchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                                        "shopId": "11111111-aaaa-bbbb-cccc-222222222222"
                                      }
                                    }
                                    """))),
            @ApiResponse(responseCode = "401", description = "Missing, unknown or revoked key, or a disallowed address",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Rejected",
                            value = """
                                    {
                                      "code": "401 UNAUTHORIZED",
                                      "message": "API key rejected.",
                                      "data": { "errorCode": "pos_key_rejected" }
                                    }
                                    """))),
            @ApiResponse(responseCode = "403", description = "The key can't act for the named shop",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Other shop",
                            value = """
                                    {
                                      "code": "403 FORBIDDEN",
                                      "message": "This API key can't act for that shop.",
                                      "data": { "errorCode": "pos_shop_not_allowed" }
                                    }
                                    """))),
            @ApiResponse(responseCode = "429", description = "Too many requests from this address (gateway)")
    })
    public ResponseEntity<ApiResult<PosApiKeyDTOs.TokenResponse>> token(
            @RequestHeader(value = API_KEY_HEADER, required = false) String apiKey,
            @RequestBody(required = false) PosApiKeyDTOs.TokenRequest body,
            HttpServletRequest request) {
        PosApiKeyDTOs.TokenResponse token = posApiKeys.exchange(apiKey,
                body == null ? null : body.shopId(), clientIp(request));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResult.ok("POS token issued", token));
    }

    /**
     * The caller's address as the host nginx saw it. nginx OVERWRITES
     * {@code X-Real-IP} and the gateway passes it through unchanged (and drops
     * {@code X-Forwarded-For}); the same header twice with one value is nginx's
     * {@code proxy_params} quirk. Absent or conflicting means the request did not
     * come through the edge: null, which an allow-listed key refuses.
     */
    static String clientIp(HttpServletRequest request) {
        List<String> values = Collections.list(request.getHeaders(CLIENT_IP_HEADER));
        String ip = null;
        for (String v : values) {
            String t = v == null ? "" : v.trim();
            if (t.isEmpty() || t.contains(",")) return null;
            if (ip != null && !ip.equals(t)) return null;
            ip = t;
        }
        return ip;
    }
}
