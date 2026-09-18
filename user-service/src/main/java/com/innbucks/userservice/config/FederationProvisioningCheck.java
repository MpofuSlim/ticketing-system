package com.innbucks.userservice.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Boot-time provisioning check for federated customer login
 * ({@code POST /auth/exchange}).
 *
 * <p>The failure this exists to catch is HALF-PROVISIONING: the feature switched
 * on in the shared cell ConfigMap while the middleware's public key lives in the
 * per-host Secret that was never updated. The cell then looks live and refuses
 * every super-app login with 503 — the same shape the ZimSwitch card rail and
 * loyalty's partner registration shipped in. ERROR, never a boot failure: a
 * login integration must not be able to stop a cell starting. Silent when the
 * feature is off.
 *
 * <p>Binds the same property spellings {@code FederationAssertionVerifier} and
 * {@code FederatedLoginService} use — do not introduce a variant here.
 */
@Component
@Slf4j
public class FederationProvisioningCheck {

    private final boolean enabled;
    private final String publicKey;
    private final String issuer;
    private final String audience;

    public FederationProvisioningCheck(
            @Value("${auth.federation.enabled:false}") boolean enabled,
            @Value("${auth.federation.public-key:}") String publicKey,
            @Value("${auth.federation.issuer:innbucks-middleware}") String issuer,
            @Value("${auth.federation.audience:innbucks-foundry}") String audience) {
        this.enabled = enabled;
        this.publicKey = publicKey;
        this.issuer = issuer;
        this.audience = audience;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void checkFederationProvisioning() {
        if (!enabled) return;
        if (publicKey == null || publicKey.isBlank()) {
            log.error("Federated login is HALF-PROVISIONED: AUTH_FEDERATION_ENABLED=true but "
                    + "AUTH_FEDERATION_PUBLIC_KEY is blank, so every POST /auth/exchange is refused 503 "
                    + "and super-app customers cannot sign in to the fleet. Provision the middleware's "
                    + "PUBLIC key in this host's cell.<iso>.local.env, or set AUTH_FEDERATION_ENABLED=false.");
            return;
        }
        log.info("Federated login is enabled: assertions signed by issuer '{}' for audience '{}' are "
                + "exchanged for CUSTOMER sessions.", issuer, audience);
    }
}
