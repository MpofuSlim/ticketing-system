package com.innbucks.userservice.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Staff accounts (V44): which email domains may hold staff authority, and how
 * invites are sent. Per cell, from {@code STAFF_*} in {@code deploy/cells/cell.<iso>.env}
 * — a k3s pod gets its whole environment through {@code envFrom}, so a key that
 * is only in {@code application.yaml} never reaches it.
 *
 * <p><b>Validated at boot</b> ({@link #afterPropertiesSet}); a bad value stops the
 * service rather than quietly widening who can be staff:
 * <ul>
 *   <li>each domain is lower-cased with {@link Locale#ROOT} and must be a plain
 *       ASCII host name — a {@code *}, a leading {@code @} or {@code .}, a
 *       trailing dot or any non-ASCII character FAILS BOOT. A wildcard or a
 *       look-alike domain here would silently hand staff eligibility to
 *       addresses nobody at InnBucks controls;</li>
 *   <li>the console base URL, when set, must be an absolute {@code https} URL
 *       with no path beyond {@code /}, query or fragment; the invite path must
 *       start with {@code /} and carry neither {@code ?} nor {@code #} — the
 *       token is appended as the fragment;</li>
 *   <li>the TTL and both quotas must be positive.</li>
 * </ul>
 *
 * <p><b>A blank domain list boots</b>, deliberately: every staff grant then
 * answers {@code 503 staff_domains_unconfigured} and {@code StaffProvisioningCheck}
 * logs a HALF-PROVISIONED error. Refusing to boot would take every other
 * user-service feature down with it.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "staff")
public class StaffAccountProperties implements InitializingBean {

    /** A host name: dot-separated ASCII labels, no leading/trailing hyphen, at least two labels. */
    static final Pattern DOMAIN = Pattern.compile(
            "^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$");

    public static final List<String> DEFAULT_DENIED_LOCAL_PARTS = List.of(
            "admin", "administrator", "info", "support", "help", "noreply", "postmaster", "hostmaster",
            "webmaster", "abuse", "security", "finance", "accounts", "billing", "sales", "contact", "office",
            "team", "all", "staff");

    /** How the mint treats a holder of staff authority who is not staff-eligible. */
    public enum Enforcement {
        /** Mint as before; count the holder on {@code user.staff.ineligible_holder}. */
        WATCH,
        /** Drop every PLATFORM permission and every NAMED staff role name from the token. */
        ENFORCE
    }

    /** {@code STAFF_ALLOWED_EMAIL_DOMAINS}: exact domains a staff address may use. */
    private List<String> allowedEmailDomains = new ArrayList<>();

    /** {@code STAFF_DENIED_LOCAL_PARTS}: shared / role mailboxes refused as a staff address. */
    private List<String> deniedLocalParts = new ArrayList<>(DEFAULT_DENIED_LOCAL_PARTS);

    /** {@code STAFF_CONSOLE_BASE_URL}: the console origin the invite link points at. */
    private String consoleBaseUrl = "";

    /** {@code STAFF_INVITE_PATH}: the console route that redeems an invite. */
    private String invitePath = "/set-password";

    /** {@code STAFF_INVITE_TTL}: how long an invite link works. */
    private Duration inviteTtl = Duration.ofHours(72);

    /** {@code STAFF_INVITE_RESEND_LIMIT}: invites per account per rolling 24 hours. */
    private int inviteResendLimit = 5;

    /** {@code STAFF_CREATE_DAILY_LIMIT}: staff accounts one caller may create per rolling 24 hours. */
    private int createDailyLimit = 20;

    /** {@code STAFF_ELIGIBILITY_ENFORCEMENT}: {@code watch} (default) or {@code enforce}. */
    private Enforcement eligibilityEnforcement = Enforcement.WATCH;

    // Normalised views, filled by afterPropertiesSet — not bindable.
    @Getter(lombok.AccessLevel.NONE) @Setter(lombok.AccessLevel.NONE)
    private Set<String> domains = Set.of();
    @Getter(lombok.AccessLevel.NONE) @Setter(lombok.AccessLevel.NONE)
    private Set<String> deniedLocalPartSet = Set.of();
    @Getter(lombok.AccessLevel.NONE) @Setter(lombok.AccessLevel.NONE)
    private String resolvedConsoleBaseUrl = "";

    @Override
    public void afterPropertiesSet() {
        this.domains = normaliseDomains(allowedEmailDomains);
        Set<String> denied = new LinkedHashSet<>();
        for (String part : deniedLocalParts == null ? List.<String>of() : deniedLocalParts) {
            if (part != null && !part.isBlank()) denied.add(part.strip().toLowerCase(Locale.ROOT));
        }
        this.deniedLocalPartSet = Collections.unmodifiableSet(denied);
        this.resolvedConsoleBaseUrl = normaliseConsoleUrl(consoleBaseUrl);
        if (invitePath == null || !invitePath.startsWith("/") || invitePath.contains("#")
                || invitePath.contains("?") || invitePath.chars().anyMatch(c -> c < 0x21 || c > 0x7E)) {
            throw new IllegalStateException("STAFF_INVITE_PATH must start with '/' and contain no '?', '#', "
                    + "space or non-ASCII character; got '" + invitePath + "'");
        }
        if (inviteTtl == null || inviteTtl.isZero() || inviteTtl.isNegative()) {
            throw new IllegalStateException("STAFF_INVITE_TTL must be a positive duration, e.g. PT72H");
        }
        if (inviteResendLimit < 1) {
            throw new IllegalStateException("STAFF_INVITE_RESEND_LIMIT must be at least 1");
        }
        if (createDailyLimit < 1) {
            throw new IllegalStateException("STAFF_CREATE_DAILY_LIMIT must be at least 1");
        }
        if (eligibilityEnforcement == null) {
            eligibilityEnforcement = Enforcement.WATCH;
        }
    }

    /**
     * Lower-cases each entry with {@link Locale#ROOT} and validates it. Anything
     * that is not a plain ASCII host name fails boot, with the offending entry
     * named so the operator can fix the env file.
     */
    static Set<String> normaliseDomains(List<String> raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw == null) return Collections.unmodifiableSet(out);
        for (String entry : raw) {
            if (entry == null || entry.isBlank()) continue;
            String stripped = entry.strip();
            if (stripped.chars().anyMatch(c -> c > 0x7E)) {
                throw new IllegalStateException("STAFF_ALLOWED_EMAIL_DOMAINS entry '" + stripped
                        + "' contains a non-ASCII character; list domains in plain ASCII (never punycode look-alikes)");
            }
            if (stripped.contains("*")) {
                throw new IllegalStateException("STAFF_ALLOWED_EMAIL_DOMAINS entry '" + stripped
                        + "' contains a wildcard; list each exact domain");
            }
            if (stripped.startsWith("@") || stripped.startsWith(".") || stripped.endsWith(".")) {
                throw new IllegalStateException("STAFF_ALLOWED_EMAIL_DOMAINS entry '" + stripped
                        + "' must be a bare domain such as innbucks.co.zw (no leading '@' or '.', no trailing dot)");
            }
            String lower = stripped.toLowerCase(Locale.ROOT);
            if (!DOMAIN.matcher(lower).matches()) {
                throw new IllegalStateException("STAFF_ALLOWED_EMAIL_DOMAINS entry '" + stripped
                        + "' is not a valid domain name");
            }
            out.add(lower);
        }
        return Collections.unmodifiableSet(out);
    }

    static String normaliseConsoleUrl(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String url = raw.strip();
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("STAFF_CONSOLE_BASE_URL is not a valid URL: '" + raw + "'");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalStateException("STAFF_CONSOLE_BASE_URL must be an absolute https URL; got '" + raw + "'");
        }
        if ((uri.getRawPath() != null && !uri.getRawPath().isEmpty()) || uri.getRawQuery() != null
                || uri.getRawFragment() != null || uri.getRawUserInfo() != null) {
            throw new IllegalStateException("STAFF_CONSOLE_BASE_URL must be an origin only (scheme, host, optional "
                    + "port), e.g. https://foundry.innbucks.co.zw; got '" + raw + "'");
        }
        return url;
    }

    /** True when at least one staff domain is configured. */
    public boolean domainsConfigured() {
        return !domains.isEmpty();
    }

    /** The configured domains, normalised, in configured order. */
    public Set<String> domains() {
        return domains;
    }

    /** Denied local parts, lower-cased. */
    public Set<String> deniedLocalPartSet() {
        return deniedLocalPartSet;
    }

    /** The console origin without a trailing slash; blank when unset. */
    public String consoleBaseUrl() {
        return resolvedConsoleBaseUrl;
    }

    public boolean enforcing() {
        return eligibilityEnforcement == Enforcement.ENFORCE;
    }
}
