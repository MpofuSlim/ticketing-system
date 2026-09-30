package com.innbucks.userservice.support;

import com.innbucks.userservice.devicesecurity.DeviceSecurityException;
import com.innbucks.userservice.devicesecurity.DeviceSupportService;
import com.innbucks.userservice.devicesecurity.dto.SupportDTOs.SupportRefLookup;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.exception.SupportPolicyException;
import com.innbucks.userservice.repository.CustomerProfileRepository;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.security.PermissionCatalog;
import com.innbucks.userservice.support.SupportQueryClassifier.Kind;
import com.innbucks.userservice.support.SupportQueryClassifier.Query;
import com.innbucks.userservice.support.dto.SupportDTOs.ConsoleAccountView;
import com.innbucks.userservice.support.dto.SupportDTOs.ConsoleSectionData;
import com.innbucks.userservice.support.dto.SupportDTOs.CustomerView;
import com.innbucks.userservice.support.dto.SupportDTOs.FocusView;
import com.innbucks.userservice.support.dto.SupportDTOs.IdentityWarning;
import com.innbucks.userservice.support.dto.SupportDTOs.InnbucksAppPhoneView;
import com.innbucks.userservice.support.dto.SupportDTOs.InnbucksAppSectionData;
import com.innbucks.userservice.support.dto.SupportDTOs.QueryView;
import com.innbucks.userservice.support.dto.SupportDTOs.SearchResult;
import com.innbucks.userservice.support.dto.SupportDTOs.SectionView;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * {@code POST /admin/support/customers/search} (design §3.1.1): classify what
 * the agent typed, resolve it to the customer's accounts, build the sections the
 * agent may see, record the lookup, and hand back a {@code lookupId} every later
 * call is bound to.
 *
 * <p>Order, and why:
 * <ol>
 *   <li><b>Count</b> the call against the agent's limit — first, so a refused or
 *       unrecognised query costs the same as a real one and the box cannot be
 *       used to probe cheaply.</li>
 *   <li><b>Classify</b>. A refusal ({@code query_not_accepted},
 *       {@code query_not_recognised}, {@code query_not_supported},
 *       {@code query_not_permitted}) is recorded with its KIND only — never the
 *       text — and nothing is resolved.</li>
 *   <li><b>Resolve and build</b>. A reference returns ONLY the section that owns
 *       it: a reference is printed on shared tickets and screenshots, so the
 *       cross-product view is always a second, explicit phone or email search
 *       (logged as its own lookup). A phone or email fans out to every section
 *       the agent may see. A section that fails renders UNAVAILABLE (soft on
 *       availability) — a section the agent may not see is never built at all
 *       (closed on authorization).</li>
 *   <li><b>Record</b> the lookup — fail-CLOSED: a lookup whose row cannot be
 *       written is not shown.</li>
 * </ol>
 *
 * <p>Deliberately not {@code @Transactional}: each section reads in its own
 * transaction, so one section's failure cannot mark a shared transaction
 * rollback-only and take the others down with it.
 */
@Service
@Slf4j
public class SupportSearchService {

    static final String OP_SEARCH = "SEARCH";
    static final String OP_SEARCH_REFUSED = "SEARCH_REFUSED";
    /** A valid query whose customer records could not be read (503 support_search_unavailable). */
    static final String OP_SEARCH_FAILED = "SEARCH_FAILED";

    /** Sections this build has, in response order, with the permission each is read with. */
    static final Map<String, String> SECTIONS = new LinkedHashMap<>();

    static {
        SECTIONS.put(ConsoleSupportSection.NAME, PermissionCatalog.SUPPORT_CONSOLE_READ);
        SECTIONS.put(InnbucksAppSupportSection.NAME, PermissionCatalog.DEVICE_SECURITY_READ);
    }

    static final String REFERENCE_NOTE = "To see this customer's other products, search by their phone or email.";

    private final SupportQueryClassifier classifier;
    private final SupportLookupLimiter limiter;
    private final SupportAccessLogWriter accessLog;
    private final SupportStaffTargets staffTargets;
    private final SupportMetrics metrics;
    private final ConsoleSupportSection console;
    private final InnbucksAppSupportSection innbucksApp;
    private final DeviceSupportService deviceSupport;
    private final UserRepository users;
    private final CustomerProfileRepository customerProfiles;
    private final SupportProperties properties;
    private final Clock clock;

    public SupportSearchService(SupportQueryClassifier classifier, SupportLookupLimiter limiter,
                                SupportAccessLogWriter accessLog, SupportStaffTargets staffTargets,
                                SupportMetrics metrics, ConsoleSupportSection console,
                                InnbucksAppSupportSection innbucksApp, DeviceSupportService deviceSupport,
                                UserRepository users, CustomerProfileRepository customerProfiles,
                                SupportProperties properties, @Qualifier("supportClock") Clock supportClock) {
        this.classifier = classifier;
        this.limiter = limiter;
        this.accessLog = accessLog;
        this.staffTargets = staffTargets;
        this.metrics = metrics;
        this.console = console;
        this.innbucksApp = innbucksApp;
        this.deviceSupport = deviceSupport;
        this.users = users;
        this.customerProfiles = customerProfiles;
        this.properties = properties;
        this.clock = supportClock;
    }

    public SearchResult search(SupportAgent agent, String q, String clientIp) {
        requireEnabled(properties);
        limiter.acquire(agent);
        Query query = classifier.classify(q);
        refuseIfNotSearchable(agent, query, clientIp);

        LocalDateTime now = LocalDateTime.now(clock);
        List<String> visible = SECTIONS.entrySet().stream()
                .filter(e -> agent.holds(e.getValue())).map(Map.Entry::getKey).toList();
        // What the agent may learn about CONSOLE accounts (identity warnings, the
        // staff flag) follows the console section: an agent who can't see it
        // must not learn of those accounts through a side channel.
        boolean consoleVisible = visible.contains(ConsoleSupportSection.NAME);

        // Resolving the query and the staff check read the records every section
        // is built from. With no answer there is no section to show as
        // UNAVAILABLE, so the search is refused — 503, and still logged.
        Resolution r;
        Set<UUID> staff;
        try {
            r = resolve(query);
            staff = staffTargets.staffAccounts(r.keys());
        } catch (DeviceSecurityException e) {
            throw e;
        } catch (RuntimeException e) {
            throw searchUnavailable(agent, query, clientIp, e);
        }
        Map<String, SectionView<?>> sections = new LinkedHashMap<>();
        Map<String, List<String>> targets = new LinkedHashMap<>();
        List<String> notShown = new ArrayList<>();
        FocusView focus = null;

        if (query.kind() == Kind.DTX_REFERENCE) {
            // The owning section only; nothing else is even built.
            sections.put(InnbucksAppSupportSection.NAME, safely(InnbucksAppSupportSection.NAME,
                    () -> r.referenceFound
                            ? innbucksApp.section(r.phones, "reference")
                            : new SectionView<>("NOT_FOUND", null,
                                    "No block, ban or unlock carries reference " + query.value() + ".",
                                    "Check the spelling with the caller, or search by their phone number.",
                                    new InnbucksAppSectionData(List.of()))));
            targets.put(InnbucksAppSupportSection.NAME, List.copyOf(r.phones.keySet()));
            focus = new FocusView(InnbucksAppSupportSection.NAME, Kind.DTX_REFERENCE.name(), query.value(),
                    REFERENCE_NOTE);
        } else {
            String matchedBy = query.kind() == Kind.EMAIL ? "email" : "phone";
            for (String name : SECTIONS.keySet()) {
                if (!visible.contains(name)) {
                    notShown.add(name);
                    continue;
                }
                if (ConsoleSupportSection.NAME.equals(name)) {
                    // Which accounts are console accounts is itself a read: inside
                    // safely(), so an outage renders this section UNAVAILABLE.
                    SectionView<?> section = safely(name, () -> console.section(
                            r.accounts.stream().filter(console::isConsoleAccount).toList(), matchedBy, agent, now));
                    sections.put(name, section);
                    // Full views only: a staff stub's account is never a target, so
                    // no detail read or write can be aimed at it (404 target_not_found).
                    targets.put(name, ConsoleSupportSection.targets(section));
                } else {
                    // A phone searched is matched by phone; the phones of an
                    // emailed account are matched through the email.
                    sections.put(name, safely(name, () -> innbucksApp.section(r.phones, matchedBy)));
                    targets.put(name, List.copyOf(r.phones.keySet()));
                }
            }
        }

        SupportCustomerKeys keys = r.keys();
        boolean staffAccount = !staff.isEmpty();

        List<IdentityWarning> warnings = consoleVisible && query.kind() != Kind.DTX_REFERENCE
                ? degrade("identity warnings", () -> warnings(query, r), () -> List.of(IDENTITY_CHECK_UNAVAILABLE))
                : List.of();
        CustomerView customer = degrade("customer name", () -> customer(query, sections, r, true),
                () -> customer(query, sections, r, false));
        String lookupId = record(agent, query, keys, sections.keySet(), targets, staffAccount, clientIp, now);
        if (staffAccount) {
            // The alert fires whoever looked; only the RESPONSE flag follows the console section.
            metrics.staffTargetLookup();
            log.warn("Support lookup matched an InnBucks STAFF account lookupId={} agent={} staffUserUuids={}",
                    lookupId, agent.userUuid(), staff);
        }
        metrics.lookup("ok");
        return new SearchResult(lookupId, new QueryView(query.kind().name(), query.value()), customer, warnings,
                sections, focus, notShown, staffAccount && consoleVisible);
    }

    static final IdentityWarning IDENTITY_CHECK_UNAVAILABLE = new IdentityWarning("identity_check_unavailable",
            "We couldn't check whether this phone number or email is on other accounts. Confirm the caller's "
                    + "details before acting.");

    static void requireEnabled(SupportProperties properties) {
        if (!properties.isEnabled()) throw SupportPolicyException.supportDisabled();
    }

    // ---- classification refusals ---------------------------------------------------------

    private void refuseIfNotSearchable(SupportAgent agent, Query query, String clientIp) {
        SupportPolicyException refusal = switch (query.kind()) {
            case NOT_ACCEPTED -> SupportPolicyException.queryNotAccepted();
            case NOT_RECOGNISED -> SupportPolicyException.queryNotRecognised();
            default -> {
                if (!query.kind().supported()) yield SupportPolicyException.queryNotSupported();
                String needed = query.kind().permission();
                if (needed != null && !agent.holds(needed)) yield SupportPolicyException.queryNotPermitted();
                boolean anySection = SECTIONS.values().stream().anyMatch(agent::holds);
                yield anySection ? null : SupportPolicyException.queryNotPermitted();
            }
        };
        if (refusal == null) return;
        metrics.lookup(refusal.getErrorCode());
        // The KIND, never the text: what the agent typed may be a card number.
        accessLog.bestEffort(SupportAccessLog.builder()
                .createdAt(LocalDateTime.now(clock))
                .op(OP_SEARCH_REFUSED)
                .outcome(refusal.getErrorCode())
                .agentUserUuid(agent.userUuid())
                .agentSubject(SupportAccessLogWriter.subject(agent.subject()))
                .queryKind(query.kind().name())
                .clientIpUntrusted(truncate(clientIp, 64))
                .build());
        throw refusal;
    }

    // ---- resolution ------------------------------------------------------------------------

    /** What a query resolves to: the accounts, the phones the app section reads, and a reference hit. */
    private static final class Resolution {
        final Query query;
        final List<User> accounts = new ArrayList<>();
        /** E.164 phone → the account using it, for the InnBucks app section. Ordered. */
        final Map<String, Optional<User>> phones = new LinkedHashMap<>();
        boolean referenceFound;

        Resolution(Query query) {
            this.query = query;
        }

        SupportCustomerKeys keys() {
            Set<String> phoneKeys = new LinkedHashSet<>(phones.keySet());
            Set<String> emails = new LinkedHashSet<>();
            Set<String> uuids = new LinkedHashSet<>();
            Set<Long> ids = new LinkedHashSet<>();
            if (query.kind() == Kind.PHONE) phoneKeys.add(query.value());
            if (query.kind() == Kind.EMAIL) emails.add(query.value());
            for (User u : accounts) {
                if (u.getPhoneNumber() != null) phoneKeys.add(u.getPhoneNumber());
                if (u.getEmail() != null) emails.add(u.getEmail());
                if (u.getUserUuid() != null) uuids.add(u.getUserUuid().toString());
                if (u.getId() != null) ids.add(u.getId());
            }
            String reference = query.kind() == Kind.DTX_REFERENCE ? query.value() : null;
            return new SupportCustomerKeys(List.copyOf(phoneKeys), List.copyOf(emails), List.copyOf(uuids),
                    List.copyOf(ids), reference);
        }
    }

    /**
     * Resolves the query to accounts and the phones the app section reads. A
     * STAFF account (SUPER_ADMIN included) still counts as found — its keys
     * drive the staff flag and the write-time check — but it lends the app
     * section nothing: an emailed staff account's sign-in phone is not listed,
     * and a staff account using a searched number is not attached to it (its
     * name and profile stay out of the response, like the console stub's).
     */
    private Resolution resolve(Query query) {
        Resolution r = new Resolution(query);
        switch (query.kind()) {
            case PHONE -> {
                Optional<User> account = users.findByPhoneNumber(query.value());
                account.ifPresent(r.accounts::add);
                r.phones.put(query.value(), account.filter(u -> !staffTargets.isStaffAccount(u)));
            }
            case EMAIL -> {
                r.accounts.addAll(users.findAllByEmailIgnoreCase(query.value()));
                for (User u : r.accounts) {
                    if (u.getPhoneNumber() != null && !r.phones.containsKey(u.getPhoneNumber())
                            && !staffTargets.isStaffAccount(u)) {
                        r.phones.put(u.getPhoneNumber(), Optional.of(u));
                    }
                }
            }
            case DTX_REFERENCE -> {
                try {
                    SupportRefLookup found = deviceSupport.bySupportRef(query.value());
                    r.referenceFound = true;
                    Optional<User> account = users.findByPhoneNumber(found.msisdn());
                    account.ifPresent(r.accounts::add);
                    r.phones.put(found.msisdn(), account.filter(u -> !staffTargets.isStaffAccount(u)));
                } catch (DeviceSecurityException e) {
                    if (e.getStatus() != HttpStatus.NOT_FOUND) throw e;
                    r.referenceFound = false;
                }
            }
            default -> throw new IllegalStateException("unsearchable kind " + query.kind());
        }
        return r;
    }

    /**
     * 503 {@code support_search_unavailable}, with a best-effort row: the query
     * was valid, so its kind and MASKED form are recorded (never the keys — none
     * were resolved).
     */
    private SupportPolicyException searchUnavailable(SupportAgent agent, Query query, String clientIp,
                                                     RuntimeException cause) {
        log.error("Support search could not read the customer records: {}", cause.getClass().getSimpleName());
        SupportPolicyException refusal = SupportPolicyException.searchUnavailable();
        metrics.lookup(refusal.getErrorCode());
        accessLog.bestEffort(SupportAccessLog.builder()
                .createdAt(LocalDateTime.now(clock))
                .op(OP_SEARCH_FAILED)
                .outcome(refusal.getErrorCode())
                .agentUserUuid(agent.userUuid())
                .agentSubject(SupportAccessLogWriter.subject(agent.subject()))
                .queryKind(query.kind().name())
                .queryMasked(SupportMasking.query(query))
                .clientIpUntrusted(truncate(clientIp, 64))
                .build());
        return refusal;
    }

    /** A read the response can do without: logged, then the fallback. */
    private <T> T degrade(String what, java.util.function.Supplier<T> call, java.util.function.Supplier<T> fallback) {
        try {
            return call.get();
        } catch (RuntimeException e) {
            log.error("Support search: {} could not be read: {}", what, e.getClass().getSimpleName());
            return fallback.get();
        }
    }

    private <T> SectionView<?> safely(String name, java.util.function.Supplier<SectionView<T>> build) {
        try {
            return build.get();
        } catch (RuntimeException e) {
            log.error("Support section {} could not be built: {}", name, e.getClass().getSimpleName());
            String label = ConsoleSupportSection.NAME.equals(name) ? "The Foundry console records"
                    : "The InnBucks app records";
            return new SectionView<>("UNAVAILABLE", null, label + " couldn't be read. Try again in a minute.",
                    null, null);
        }
    }

    // ---- the customer and the warnings ------------------------------------------------------

    /**
     * Built ONLY from sections the agent can see: a field is filled when the
     * searched key names it, or when every visible account agrees on one value.
     */
    private CustomerView customer(Query query, Map<String, SectionView<?>> sections, Resolution r,
                                  boolean withProfiles) {
        Set<String> phones = new LinkedHashSet<>();
        Set<String> emails = new LinkedHashSet<>();
        Set<String> names = new LinkedHashSet<>();
        if (sections.get(ConsoleSupportSection.NAME) instanceof SectionView<?> s
                && s.data() instanceof ConsoleSectionData data) {
            for (var entry : data.accounts()) {
                // A staff stub carries nothing to take.
                if (!(entry instanceof ConsoleAccountView a)) continue;
                if (a.phone() != null) phones.add(a.phone());
                if (a.email() != null) emails.add(a.email().toLowerCase(Locale.ROOT));
                if (a.name() != null) names.add(a.name());
            }
        }
        if (sections.get(InnbucksAppSupportSection.NAME) instanceof SectionView<?> s
                && s.data() instanceof InnbucksAppSectionData data) {
            for (InnbucksAppPhoneView p : data.phones()) {
                phones.add(p.msisdn());
                Optional<User> account = r.phones.getOrDefault(p.msisdn(), Optional.empty());
                Optional<String> profileName = withProfiles
                        ? account.flatMap(u -> customerProfiles.findByUserId(u.getId()))
                                .map(cp -> cp.getFullName())
                                .filter(n -> !n.isBlank())
                        : Optional.empty();
                profileName.ifPresentOrElse(names::add, () -> account.map(ConsoleSupportSection::name)
                        .ifPresent(names::add));
            }
        }
        String phone = query.kind() == Kind.PHONE ? query.value() : (phones.size() == 1 ? phones.iterator().next() : null);
        String email = query.kind() == Kind.EMAIL ? query.value() : (emails.size() == 1 ? emails.iterator().next() : null);
        String name = names.size() == 1 ? names.iterator().next() : null;
        return new CustomerView(phone, email, name);
    }

    /** Called only for an agent who can see the console section, and never for a reference search. */
    private List<IdentityWarning> warnings(Query query, Resolution r) {
        List<IdentityWarning> out = new ArrayList<>();
        if (r.accounts.size() > 1) {
            out.add(new IdentityWarning("multiple_accounts", "This " + (query.kind() == Kind.EMAIL ? "email" : "number")
                    + " is on " + r.accounts.size() + " accounts. Check which one the caller means before acting."));
        }
        // A phone and an email that belong to different accounts: the searched
        // key's accounts, against the accounts the OTHER identifier resolves to.
        Set<Long> mine = new LinkedHashSet<>();
        r.accounts.forEach(u -> mine.add(u.getId()));
        boolean differ = false;
        for (User u : r.accounts) {
            if (query.kind() == Kind.EMAIL && u.getPhoneNumber() != null) {
                differ |= users.findByPhoneNumber(u.getPhoneNumber())
                        .filter(other -> !mine.contains(other.getId())).isPresent();
            } else if (query.kind() == Kind.PHONE && u.getEmail() != null) {
                differ |= users.findAllByEmailIgnoreCase(u.getEmail()).stream()
                        .anyMatch(other -> !mine.contains(other.getId()));
            }
        }
        if (differ) {
            out.add(new IdentityWarning("phone_email_different_accounts", "This phone number and this email belong "
                    + "to different accounts. Check which one the caller means before acting."));
        }
        return out;
    }

    // ---- the record -----------------------------------------------------------------------

    /** Writes the SEARCH row (fail-closed) and returns its lookup id; re-draws on the rare id collision. */
    private String record(SupportAgent agent, Query query, SupportCustomerKeys keys, Set<String> sections,
                          Map<String, List<String>> targets, boolean staffAccount, String clientIp,
                          LocalDateTime now) {
        String keysJson = SupportAccessLogWriter.json(keys);
        String targetsJson = SupportAccessLogWriter.json(targets);
        for (int attempt = 0; ; attempt++) {
            String lookupId = SupportLookupIds.next();
            try {
                accessLog.requiredWithUniqueLookupId(SupportAccessLog.builder()
                        .createdAt(now)
                        .lookupId(lookupId)
                        .op(OP_SEARCH)
                        .outcome("OK")
                        .agentUserUuid(agent.userUuid())
                        .agentSubject(SupportAccessLogWriter.subject(agent.subject()))
                        .queryKind(query.kind().name())
                        .queryMasked(SupportMasking.query(query))
                        .customerKeys(keysJson)
                        .sections(String.join(",", sections))
                        .sectionTargets(targetsJson)
                        .staffAccount(staffAccount)
                        .clientIpUntrusted(truncate(clientIp, 64))
                        .build());
                return lookupId;
            } catch (DataIntegrityViolationException e) {
                if (attempt >= 2) {
                    log.error("Support lookup id collided three times; refusing the lookup");
                    throw SupportPolicyException.logUnavailable();
                }
            }
        }
    }

    static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
