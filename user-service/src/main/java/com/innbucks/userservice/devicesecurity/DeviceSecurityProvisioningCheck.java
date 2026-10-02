package com.innbucks.userservice.devicesecurity;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Boot-time check for DTX device security. The failure it exists to catch is
 * HALF-PROVISIONING — the switch flipped in the shared cell ConfigMap while a
 * credential in the per-host Secret was never set — which leaves a cell that
 * looks live and answers every sign-in 503 or 401. ERROR, never a boot failure:
 * a sign-in integration must not stop a cell starting. Silent when off.
 */
@Component
@Slf4j
public class DeviceSecurityProvisioningCheck {

    private final DeviceSecurityProperties properties;
    private final StagingClientServiceClient staging;
    private final LoginTicketSigner signer;

    public DeviceSecurityProvisioningCheck(DeviceSecurityProperties properties, StagingClientServiceClient staging,
                                           LoginTicketSigner signer) {
        this.properties = properties;
        this.staging = staging;
        this.signer = signer;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void check() {
        if (!properties.isEnabled()) return;
        List<String> missing = new ArrayList<>();
        if (blank(properties.getBrokerApiKey())) missing.add("DEVICE_SECURITY_BROKER_API_KEY");
        if (blank(properties.getUssdApiKey())) missing.add("DEVICE_SECURITY_USSD_API_KEY");
        if (!staging.isConfigured()) {
            missing.add("DEVICE_SECURITY_STAGING_API_KEY / _USERNAME / _PASSWORD");
        }
        if (!signer.isConfigured()) missing.add("DEVICE_SECURITY_TICKET_PRIVATE_KEY");
        if (!missing.isEmpty()) {
            log.error("Device security is HALF-PROVISIONED: DEVICE_SECURITY_ENABLED=true but {} {} blank. "
                            + "Sign-ins that need a TOKEN answer 503 and the affected partner calls answer 401. "
                            + "Provision them in this host's cell.<iso>.local.env, or set DEVICE_SECURITY_ENABLED=false.",
                    missing, missing.size() == 1 ? "is" : "are");
        }
        for (String[] key : new String[][]{{"DEVICE_SECURITY_BROKER_API_KEY", properties.getBrokerApiKey()},
                {"DEVICE_SECURITY_USSD_API_KEY", properties.getUssdApiKey()}}) {
            if (!blank(key[1]) && key[1].trim().length() < 32) {
                log.error("{} is shorter than 32 characters. It is the only thing between the public internet and "
                        + "the {} endpoints — generate one with: openssl rand -base64 48", key[0],
                        key[0].contains("USSD") ? "*569#" : "broker");
            }
        }
        if (!blank(properties.getBrokerApiKey()) && properties.getBrokerApiKey().equals(properties.getUssdApiKey())) {
            log.error("DEVICE_SECURITY_BROKER_API_KEY and DEVICE_SECURITY_USSD_API_KEY are EQUAL: a leak of either "
                    + "partner's key would open the other's endpoints. Give each its own value.");
        }
        Set<IntegrityThreat> waived = properties.getRisk().getWaivedThreats();
        if (!waived.isEmpty()) {
            log.warn("Device security is NOT banning on integrity finding(s) {} (DEVICE_SECURITY_WAIVED_THREATS). "
                    + "They are logged as INTEGRITY_<name>_WAIVED and ignored. This is a temporary waiver for a "
                    + "mis-configured app build; clear it once the fixed build ships.", waived);
        }
        DeviceSecurityProperties.Enforce e = properties.getEnforce();
        if (!e.isOtp() && !e.isBlocks() && !e.isBans()) {
            log.warn("Device security is in WATCH MODE: every decision is logged but every caller gets TOKEN. "
                    + "Flip DEVICE_SECURITY_ENFORCE_OTP, then _BLOCKS, then _BANS (contract §14).");
        } else {
            log.info("Device security enforcing otp={} blocks={} bans={}", e.isOtp(), e.isBlocks(), e.isBans());
        }
        DeviceSecurityProperties.TestOtp t = properties.getTestOtp();
        if (t.isEnabled()) {
            if (properties.isProduction()) {
                log.error("DEVICE_SECURITY_TEST_OTP_ENABLED=true on a cell marked production: the fixed code is REFUSED "
                        + "here. Remove it from this host's env.");
            } else {
                log.warn("Device security fixed test OTP is ACTIVE for {} number(s). Never on production.",
                        t.getNumbers() == null ? 0 : t.getNumbers().size());
            }
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
