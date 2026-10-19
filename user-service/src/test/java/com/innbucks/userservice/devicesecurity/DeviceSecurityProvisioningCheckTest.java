package com.innbucks.userservice.devicesecurity;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The boot line says which enforcement families are on and whether one phone per number
 * is, and a cell that switched the rule on while codes are off is WARNED that it signs no
 * phone out (only a phone that proved the SIM with a code in this sign-in replaces the
 * others). Everything else here is provisioned, so no ERROR is expected in any case.
 */
class DeviceSecurityProvisioningCheckTest {

    private static final String ENFORCING_PREFIX = "Device security enforcing ";
    private static final String WATCH_MODE = "Device security is in WATCH MODE: every decision is logged but every "
            + "caller gets TOKEN. Flip DEVICE_SECURITY_ENFORCE_OTP, then _BLOCKS, then _BANS (contract §14). "
            + "singleDevice=";
    private static final String SINGLE_DEVICE_WARN = "DEVICE_SECURITY_SINGLE_DEVICE=true signs no phone out while "
            + "DEVICE_SECURITY_ENFORCE_OTP=false: a phone signs the number's other phones out only after proving "
            + "the SIM with a code. Your devices still answers only the signed-in phone (x-device-id).";

    private final StagingClientServiceClient staging = mock(StagingClientServiceClient.class);
    private final LoginTicketSigner signer = mock(LoginTicketSigner.class);
    private final DeviceSecurityProperties properties = new DeviceSecurityProperties();
    private final Logger logger = (Logger) LoggerFactory.getLogger(DeviceSecurityProvisioningCheck.class);
    private ListAppender<ILoggingEvent> logs;
    private Level previousLevel;

    @BeforeEach
    void setUp() {
        when(staging.isConfigured()).thenReturn(true);
        when(signer.isConfigured()).thenReturn(true);
        properties.setEnabled(true);
        // Long and distinct, so neither the length check nor the equal-keys check fires.
        properties.setBrokerApiKey("broker-key-0123456789abcdefghijklmnopqrstuvwxyz");
        properties.setUssdApiKey("ussd-key-ZYXWVUTSRQPONMLKJIHGFEDCBA9876543210");

        logs = new ListAppender<>();
        logs.start();
        previousLevel = logger.getLevel();
        logger.setLevel(Level.INFO);
        logger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
        logger.setLevel(previousLevel);
    }

    private void enforce(boolean otp, boolean blocks, boolean bans, boolean singleDevice) {
        properties.getEnforce().setOtp(otp);
        properties.getEnforce().setBlocks(blocks);
        properties.getEnforce().setBans(bans);
        properties.getTrust().setSingleDevice(singleDevice);
    }

    private void check() {
        new DeviceSecurityProvisioningCheck(properties, staging, signer).check();
    }

    private List<String> at(Level level) {
        return logs.list.stream().filter(e -> e.getLevel() == level).map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    @DisplayName("every family enforcing, one phone per number off: the INFO boot line says singleDevice=false "
            + "and nothing is WARNED")
    void enforcing_singleDeviceOff() {
        enforce(true, true, true, false);
        check();

        assertThat(at(Level.INFO)).containsExactly(
                "Device security enforcing otp=true blocks=true bans=true singleDevice=false");
        assertThat(at(Level.WARN)).noneMatch(m -> m.contains("SINGLE_DEVICE")).isEmpty();
        assertThat(at(Level.ERROR)).isEmpty();
    }

    @Test
    @DisplayName("one phone per number on with codes enforced: the INFO boot line says singleDevice=true and no "
            + "SINGLE_DEVICE WARN — a fresh code is what lets a phone sign the others out")
    void enforcing_singleDeviceOn_otpOn() {
        enforce(true, true, true, true);
        check();

        assertThat(at(Level.INFO)).containsExactly(
                "Device security enforcing otp=true blocks=true bans=true singleDevice=true");
        assertThat(at(Level.WARN)).noneMatch(m -> m.contains("SINGLE_DEVICE")).isEmpty();
        assertThat(at(Level.ERROR)).isEmpty();
    }

    @Test
    @DisplayName("one phone per number on with codes OFF (blocks and bans on): still the INFO enforcing line, plus "
            + "the WARN that the rule signs no phone out while DEVICE_SECURITY_ENFORCE_OTP=false")
    void enforcing_singleDeviceOn_otpOff() {
        enforce(false, true, true, true);
        check();

        assertThat(at(Level.INFO)).containsExactly(
                "Device security enforcing otp=false blocks=true bans=true singleDevice=true");
        assertThat(at(Level.WARN)).containsExactly(SINGLE_DEVICE_WARN);
        assertThat(at(Level.WARN).get(0)).startsWith("DEVICE_SECURITY_SINGLE_DEVICE=true signs no phone out while "
                + "DEVICE_SECURITY_ENFORCE_OTP=false");
        assertThat(at(Level.ERROR)).isEmpty();
    }

    @Test
    @DisplayName("watch mode (every family off) with one phone per number on: the WATCH MODE WARN ends "
            + "singleDevice=true, the SINGLE_DEVICE WARN follows it, and there is no 'enforcing' INFO line")
    void watchMode_singleDeviceOn() {
        enforce(false, false, false, true);
        check();

        List<String> warns = at(Level.WARN);
        assertThat(warns).containsExactly(WATCH_MODE + "true", SINGLE_DEVICE_WARN);
        assertThat(warns.get(0)).startsWith("Device security is in WATCH MODE").endsWith("singleDevice=true");
        assertThat(warns.get(1)).startsWith("DEVICE_SECURITY_SINGLE_DEVICE=true signs no phone out while "
                + "DEVICE_SECURITY_ENFORCE_OTP=false");
        assertThat(at(Level.INFO)).noneMatch(m -> m.startsWith(ENFORCING_PREFIX));
        assertThat(at(Level.ERROR)).isEmpty();
    }

    @Test
    @DisplayName("watch mode with one phone per number off: the WATCH MODE WARN ends singleDevice=false and no "
            + "SINGLE_DEVICE WARN is logged")
    void watchMode_singleDeviceOff() {
        enforce(false, false, false, false);
        check();

        assertThat(at(Level.WARN)).containsExactly(WATCH_MODE + "false");
        assertThat(at(Level.INFO)).noneMatch(m -> m.startsWith(ENFORCING_PREFIX));
        assertThat(at(Level.ERROR)).isEmpty();
    }

    @Test
    @DisplayName("device security switched off: the check is silent, even with one phone per number on and codes "
            + "off")
    void disabled_isSilent() {
        properties.setEnabled(false);
        enforce(false, true, true, true);
        check();

        assertThat(logs.list).isEmpty();
    }
}
