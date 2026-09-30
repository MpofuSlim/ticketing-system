package com.innbucks.userservice.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Boot-time provisioning check for customer support. ERROR, never a boot
 * failure: a missing signing key must not stop a cell starting (a MALFORMED
 * one does — {@link SupportAssertionSigner} throws at construction, which is
 * the fail-fast the operator needs when they pasted the wrong thing).
 *
 * <p>HALF-PROVISIONED = support is on but {@code SUPPORT_ASSERTION_PRIVATE_KEY}
 * is blank. Nothing in this release calls a product server-to-server (the
 * console and InnBucks 2.0 sections are in-process), so today only the log line
 * says so; once the Ticketize, InnRewards and Marketplace sections land, each
 * renders {@code UNAVAILABLE} rather than calling a product without an
 * assertion. The key comes from the {@code user-service-support-signing}
 * Secret — never from {@code cell-zw-secrets}, which every pod receives.
 */
@Slf4j
@Component
public class SupportProvisioningCheck {

    private final SupportProperties properties;
    private final SupportAssertionSigner signer;

    public SupportProvisioningCheck(SupportProperties properties, SupportAssertionSigner signer) {
        this.properties = properties;
        this.signer = signer;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void checkSupportProvisioning() {
        if (!properties.isEnabled()) {
            log.info("Customer support is OFF (SUPPORT_ENABLED=false): /admin/support/** answers 404.");
            return;
        }
        if (!signer.isConfigured()) {
            log.error("Customer support HALF-PROVISIONED: SUPPORT_ASSERTION_PRIVATE_KEY is blank, so no support "
                    + "assertion can be signed and every product section that calls a service (Ticketize, "
                    + "InnRewards, Marketplace) renders UNAVAILABLE. Create the user-service-support-signing "
                    + "Secret (key SUPPORT_ASSERTION_PRIVATE_KEY) and restart user-service. The Foundry console "
                    + "and InnBucks 2.0 sections are in-process and unaffected.");
        } else {
            log.info("Customer support: assertion signing key loaded (kid={}, ttl={}s); lookups bound for {}, "
                            + "limit {} per {} and {} a day per agent.",
                    signer.keyId(), properties.getAssertion().getTtl().toSeconds(), properties.getLookupBindingTtl(),
                    properties.getLimiter().getShortWindowMax(), properties.getLimiter().getShortWindow(),
                    properties.getLimiter().getDailyMax());
        }
    }
}
