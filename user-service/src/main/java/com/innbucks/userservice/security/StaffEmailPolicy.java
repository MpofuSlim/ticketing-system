package com.innbucks.userservice.security;

import com.innbucks.userservice.config.StaffAccountProperties;
import com.innbucks.userservice.exception.StaffPolicyException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Which email addresses may hold staff authority (V44) — the one place the rule
 * is written, so the create endpoint, every role grant and the invite redemption
 * cannot disagree about it.
 *
 * <p>{@link #evaluate} is the algorithm, exactly:
 * <ol>
 *   <li>strip leading and trailing Unicode whitespace ({@link String#strip()});</li>
 *   <li>refuse any character outside printable ASCII (0x21–0x7E) — the address
 *       is NEVER normalised through NFKC or punycode, because that is how a
 *       look-alike becomes a match. In the domain it is
 *       {@code email_domain_not_allowed}; in the local part
 *       {@code email_not_accepted} ({@code non_ascii});</li>
 *   <li>split at the LAST {@code @}, so {@code "x@innbucks.co.zw"@evil.com} and
 *       {@code a@innbucks.co.zw@evil.com} are judged by the domain the mail
 *       really goes to (and refused); a second {@code @} left in the local part is
 *       then refused too;</li>
 *   <li>lower-case the domain with {@link Locale#ROOT} — never the default
 *       locale, under which Turkish turns {@code I} into a dotless {@code ı};</li>
 *   <li>accept the domain only when it EXACTLY equals a configured one: no
 *       subdomain, no suffix or prefix match, no trailing-dot FQDN, no
 *       {@code xn--} label;</li>
 *   <li>check the local part, lower-cased with {@link Locale#ROOT}: a {@code +}
 *       is {@code plus_tag} (a sub-address would let one mailbox hold several
 *       accounts); it must match {@code ^[a-z0-9]([a-z0-9._-]{0,62}[a-z0-9])?$}
 *       with no {@code ..} ({@code local_part}); a shared or role mailbox on
 *       {@code staff.denied-local-parts} is {@code shared_mailbox} — whoever
 *       clicks first would own the account;</li>
 *   <li>return the lower-cased address, which is what is stored.</li>
 * </ol>
 *
 * <p>Distribution lists and aliases that are not on the denylist cannot be
 * detected here; the invite's binding to the address it was sent to limits the
 * damage.
 *
 * <p>The rule runs ONE way: a staff account must be on a staff domain, but an
 * address on a staff domain is not thereby staff. Registration, tier-2, shop
 * staff, team members and approvals accept InnBucks addresses like any other
 * (owner decision, 2026-10-08); such an account gains no staff authority, which
 * needs an email proven by redeeming an invite and an accepted staff profile
 * ({@link com.innbucks.userservice.service.StaffEligibility}).
 */
@Component
public class StaffEmailPolicy {

    public static final String EMAIL_DOMAIN_NOT_ALLOWED = "email_domain_not_allowed";
    public static final String EMAIL_NOT_ACCEPTED = "email_not_accepted";

    public static final String REASON_PLUS_TAG = "plus_tag";
    public static final String REASON_LOCAL_PART = "local_part";
    public static final String REASON_NON_ASCII = "non_ascii";
    public static final String REASON_SHARED_MAILBOX = "shared_mailbox";

    public static final String NOT_ACCEPTED_MESSAGE =
            "Use the person's own InnBucks address, without a +tag, special characters or a shared mailbox name.";

    private static final Pattern LOCAL_PART = Pattern.compile("^[a-z0-9]([a-z0-9._-]{0,62}[a-z0-9])?$");

    private final StaffAccountProperties properties;

    public StaffEmailPolicy(StaffAccountProperties properties) {
        this.properties = properties;
    }

    /** The outcome: {@code ok} with the lower-cased address, or the refusal code and reason. */
    public record Result(boolean ok, String normalized, String errorCode, String reason) {
        static Result accept(String normalized) {
            return new Result(true, normalized, null, null);
        }

        static Result domainNotAllowed() {
            return new Result(false, null, EMAIL_DOMAIN_NOT_ALLOWED, null);
        }

        static Result notAccepted(String reason) {
            return new Result(false, null, EMAIL_NOT_ACCEPTED, reason);
        }
    }

    /** The algorithm, against the configured domains and denylist. */
    public Result evaluate(String raw) {
        return evaluate(raw, properties.domains(), properties.deniedLocalPartSet());
    }

    /**
     * The algorithm, pure — {@code allowedDomains} and {@code deniedLocalParts}
     * already lower-cased (as {@link StaffAccountProperties} holds them).
     */
    public static Result evaluate(String raw, Collection<String> allowedDomains, Collection<String> deniedLocalParts) {
        if (raw == null) return Result.notAccepted(REASON_LOCAL_PART);
        String address = raw.strip();
        int at = address.lastIndexOf('@');
        if (at <= 0 || at == address.length() - 1) {
            // No usable split: nothing that could be judged a domain.
            return at == address.length() - 1 ? Result.domainNotAllowed() : Result.notAccepted(REASON_LOCAL_PART);
        }
        String local = address.substring(0, at);
        String domain = address.substring(at + 1);
        if (!printableAscii(domain)) {
            return Result.domainNotAllowed();
        }
        String lowerDomain = domain.toLowerCase(Locale.ROOT);
        if (allowedDomains == null || !allowedDomains.contains(lowerDomain)) {
            return Result.domainNotAllowed();
        }
        if (!printableAscii(local)) {
            return Result.notAccepted(REASON_NON_ASCII);
        }
        String lowerLocal = local.toLowerCase(Locale.ROOT);
        if (lowerLocal.indexOf('+') >= 0) {
            return Result.notAccepted(REASON_PLUS_TAG);
        }
        if (lowerLocal.indexOf('@') >= 0 || lowerLocal.contains("..") || !LOCAL_PART.matcher(lowerLocal).matches()) {
            return Result.notAccepted(REASON_LOCAL_PART);
        }
        if (deniedLocalParts != null && deniedLocalParts.contains(lowerLocal)) {
            return Result.notAccepted(REASON_SHARED_MAILBOX);
        }
        return Result.accept(lowerLocal + "@" + lowerDomain);
    }

    /**
     * The address a staff account may use, lower-cased — or a 400:
     * {@code email_domain_not_allowed} (with {@code field} and
     * {@code allowedDomains}) or {@code email_not_accepted} (with {@code field}
     * and {@code reason}). 503 {@code staff_domains_unconfigured} when no domain
     * is configured on this cell.
     */
    public String require(String raw) {
        requireConfigured();
        Result result = evaluate(raw);
        if (result.ok()) return result.normalized();
        throw refusal(result);
    }

    /** 503 {@code staff_domains_unconfigured} when this cell names no staff domain. */
    public void requireConfigured() {
        if (!properties.domainsConfigured()) {
            throw StaffPolicyException.staffDomainsUnconfigured();
        }
    }

    /** The exception for a refused {@link Result}. */
    public StaffPolicyException refusal(Result result) {
        if (EMAIL_DOMAIN_NOT_ALLOWED.equals(result.errorCode())) {
            return domainNotAllowed();
        }
        return new StaffPolicyException(org.springframework.http.HttpStatus.BAD_REQUEST, EMAIL_NOT_ACCEPTED,
                NOT_ACCEPTED_MESSAGE, java.util.Map.of("field", "email", "reason", result.reason()));
    }

    /** 400 {@code email_domain_not_allowed}, naming the configured domains. */
    public StaffPolicyException domainNotAllowed() {
        List<String> domains = new ArrayList<>(properties.domains());
        return new StaffPolicyException(org.springframework.http.HttpStatus.BAD_REQUEST, EMAIL_DOMAIN_NOT_ALLOWED,
                domainNotAllowedMessage(domains),
                java.util.Map.of("field", "email", "allowedDomains", domains));
    }

    /** "Staff accounts must use an InnBucks email address ending in @a or @b." */
    public static String domainNotAllowedMessage(List<String> domains) {
        StringBuilder text = new StringBuilder("Staff accounts must use an InnBucks email address ending in ");
        for (int i = 0; i < domains.size(); i++) {
            if (i > 0) text.append(i == domains.size() - 1 ? " or " : ", ");
            text.append('@').append(domains.get(i));
        }
        return text.append('.').toString();
    }

    /** True when {@code email}'s domain passes {@link #evaluate}'s domain step under current config. */
    public boolean domainAllowed(String email) {
        if (email == null) return false;
        String address = email.strip();
        int at = address.lastIndexOf('@');
        if (at < 0 || at == address.length() - 1) return false;
        String domain = address.substring(at + 1);
        return printableAscii(domain) && properties.domains().contains(domain.toLowerCase(Locale.ROOT));
    }

    private static boolean printableAscii(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x21 || c > 0x7E) return false;
        }
        return true;
    }
}
