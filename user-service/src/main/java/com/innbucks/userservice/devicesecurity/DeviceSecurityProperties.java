package com.innbucks.userservice.devicesecurity;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for DTX device security ({@code device-security.*}). Defaults
 * are the contract's suggested thresholds (§8, §9.3, §11) so a cell needs to set
 * only credentials and the rollout switches.
 *
 * <p><b>Rollout (§14).</b> {@link #enabled} off = every device-security endpoint
 * 404s. On with every {@link Enforce} flag false is WATCH mode: DTX makes and
 * logs every decision but answers TOKEN to everyone, so two weeks of logs show
 * how many real customers each rule would stop before anyone is stopped. Then
 * flip {@code otp}, then {@code blocks}, then {@code bans}. Blocks, bans and
 * unlocks a PERSON makes (call center, fraud desk, the customer on *569#) are
 * always enforced — watch mode is for the automatic rules only.
 */
@Data
@ConfigurationProperties(prefix = "device-security")
public class DeviceSecurityProperties {

    /** Off = 404 on every device-security endpoint (admin views stay readable). */
    private boolean enabled = false;

    /**
     * Whether this cell serves real customers. On production an integrity source
     * of {@code unavailable} (a developer build) is itself a risk signal (§6.1),
     * and the fixed test OTP is refused outright.
     */
    private boolean production = true;

    /** Shared key the broker presents as {@code x-api-key} on every app-facing and broker call. */
    private String brokerApiKey = "";

    /** Shared key the *569# USSD service presents as {@code x-api-key}. */
    private String ussdApiKey = "";

    /** Call-center number quoted in support-only messages. Blank = "InnBucks support". */
    private String supportPhone = "";

    private Enforce enforce = new Enforce();
    private Staging staging = new Staging();
    private Ticket ticket = new Ticket();
    private Otp otp = new Otp();
    private TestOtp testOtp = new TestOtp();
    private Trust trust = new Trust();
    private Blocks blocks = new Blocks();
    private Risk risk = new Risk();
    private Ussd ussd = new Ussd();
    private Lookup lookup = new Lookup();
    private Retention retention = new Retention();

    /**
     * Which automatic rule families answer for real. All enforce by default — the
     * app and DTX launch together; all false is watch mode, an explicit opt-out.
     */
    @Data
    public static class Enforce {
        /** OTP for new, changed, expired or risky devices. */
        private boolean otp = true;
        /** Temporary blocks (§8.4). */
        private boolean blocks = true;
        /** Bans (§8.5). */
        private boolean bans = true;
    }

    /** Staging's {@code /auth/client-service} — the credential only DTX holds (§3 rule 2). */
    @Data
    public static class Staging {
        private String baseUrl = "https://staging.innbucks.co.zw";
        private String apiKey = "";
        private String username = "";
        private String password = "";
        private int connectTimeoutMs = 3000;
        private int readTimeoutMs = 15000;
        /** How long to reuse a token whose lifetime staging does not state. */
        private Duration fallbackTokenTtl = Duration.ofMinutes(10);
        /** Refresh this long before a cached token expires, so no caller is handed one about to die. */
        private Duration refreshMargin = Duration.ofSeconds(60);
    }

    /** The login ticket (§3 rule 3). RS256; the broker verifies with the published public key. */
    @Data
    public static class Ticket {
        /** PKCS#8 PEM ({@code -----BEGIN PRIVATE KEY-----}). */
        private String privateKey = "";
        /** Stamped as {@code kid} so a rotation can overlap. */
        private String keyId = "dtx-ticket-1";
        private String issuer = "innbucks-dtx";
        private String audience = "innbucks-broker";
        private Duration ttl = Duration.ofMinutes(2);
        /** Lifetime of the proof an in-session step-up returns (§5.5). */
        private Duration stepUpProofTtl = Duration.ofMinutes(5);
    }

    /** OTP rules (§11, FIRM). */
    @Data
    public static class Otp {
        private Duration codeTtl = Duration.ofMinutes(5);
        /** How long a challenge waits for the app to pick a channel before it dies unused. */
        private Duration challengeTtl = Duration.ofMinutes(10);
        private int attempts = 3;
        private Duration resendAfter = Duration.ofSeconds(60);
        private int maxResends = 2;
        private int perNumberHourly = 5;
        private int perNumberDaily = 20;
        private int perDeviceHourly = 5;
        private int perDeviceDaily = 20;
        /**
         * Per network address. Deliberately looser than per number: mobile networks
         * put thousands of customers behind one carrier-grade NAT address.
         */
        private int perIpHourly = 30;
        private OtpChannel defaultChannel = OtpChannel.WHATSAPP;
        /** A channel whose provider just failed is left out of {@code channels} for this long. */
        private Duration channelDownFor = Duration.ofMinutes(2);
    }

    /** Fixed code for allow-listed test numbers (§11, §14.1). Never on production. */
    @Data
    public static class TestOtp {
        private boolean enabled = false;
        private String code = "";
        private List<String> numbers = new ArrayList<>();
    }

    /** Trust windows and the device-count rules (§8.3, §8.6). */
    @Data
    public static class Trust {
        private int days = 90;
        /** Trust window for a customer with a past fraud flag. */
        private int fraudFlagDays = 30;
        private Duration cooling = Duration.ofHours(24);
        /**
         * After a correct OTP, how long the device may be issued fresh tickets
         * without another code, so a mistyped PIN costs a retry rather than an SMS.
         * Staging's PIN lock-out stays the authority on wrong PINs.
         */
        private Duration pendingPinGrace = Duration.ofMinutes(10);
        /** More trusted devices than this = OTP, and the app shows the list to tidy. */
        private int maxTrustedDevices = 3;
        /** A device bound to more customers than this is the mule-farm pattern (§8.5, §17). */
        private int maxCustomersPerDevice = 2;
        private int dormantDays = 30;
        /** How long a PIN issue or reset keeps sign-ins elevated (§5.7, §8.3). */
        private int pinIssueElevatedDays = 7;
        /** An in-session step-up is asked only of devices bound this recently (§8.3). */
        private Duration sessionStepUpWindow = Duration.ofHours(24);
    }

    /** The temporary-block ladder (§8.4). */
    @Data
    public static class Blocks {
        private List<Duration> ladder = new ArrayList<>(List.of(
                Duration.ofMinutes(15), Duration.ofHours(1), Duration.ofHours(24)));
        private int windowDays = 30;
        /** The Nth temporary block inside the window becomes a ban. */
        private int banOnBlockNumber = 4;
        /** OTP attempts used up on this many challenges in a row = a block. */
        private int deadChallengesInARow = 2;
        /** Bounds on a call-center temporary block. */
        private Duration supportMin = Duration.ofMinutes(15);
        private Duration supportMax = Duration.ofDays(7);
    }

    /** Risk-engine thresholds (§8.1–§8.3). DTX owns and tunes these. */
    @Data
    public static class Risk {
        /** A combined score at or above this is a temporary block rather than an OTP. */
        private int blockScore = 90;
        private double impossibleTravelKmh = 900;
        /** How far apart two fixes must be before speed is judged (rounding noise). */
        private double impossibleTravelMinKm = 50;
        private double placeRadiusKm = 25;
        /** Successful sign-ins near a point before it is a known place. */
        private int placeMinVisits = 3;
        /** Located successful sign-ins a customer needs before "unknown place" is judged at all. */
        private int placeMinHistory = 5;
        private int numbersPerDeviceHourly = 3;
        private int wrongPinsPerDay = 3;
    }

    /** *569# safeguards (§9.3). */
    @Data
    public static class Ussd {
        private int maxUnlockAttemptsPerDay = 3;
        /** One successful unlock per device per this window; a re-ban inside it is support only. */
        private Duration unlockCooldown = Duration.ofDays(7);
        /** A fraud-desk suspicion ban can be lifted on USSD only after this long. */
        private Duration fraudSuspectedUnlockDelay = Duration.ofHours(24);
        private int maxBlocksPerDay = 5;
    }

    /** The number check (§5.7): never an OTP, so a tight limit instead. */
    @Data
    public static class Lookup {
        private int perNumberHourly = 10;
        private int perDeviceHourly = 20;
    }

    /** What is kept, for how long (§12). */
    @Data
    public static class Retention {
        private int eventsDays = 365;
        private int locationsDays = 365;
        private int devicesDays = 365;
        private int challengesDays = 7;
        private int ticketsDays = 7;
    }
}
