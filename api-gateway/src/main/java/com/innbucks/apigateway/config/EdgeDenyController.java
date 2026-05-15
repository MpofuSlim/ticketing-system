package com.innbucks.apigateway.config;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal forward target for routes that need to short-circuit at the
 * gateway with a 404 (e.g. {@code /loyalty/internal/**}). Spring Cloud
 * Gateway dispatches the request to {@code forward:/__edge_deny__}; this
 * handler returns 404 with an empty body, identical to any other
 * non-existent path on the gateway.
 *
 * <p>Returning 404 (rather than 401/403) is deliberate: it doesn't tell
 * attackers an endpoint exists at all. Service-to-service callers reach
 * loyalty-service directly via the cluster network and never come through
 * the gateway, so they're unaffected by this rule.
 */
@RestController
class EdgeDenyController {

    @RequestMapping("/__edge_deny__")
    public ResponseEntity<Void> deny() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }
}
