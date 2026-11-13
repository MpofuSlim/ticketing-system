package com.innbucks.eventservice.service;

import com.innbucks.eventservice.client.OrganizerGateway;
import com.innbucks.eventservice.client.SalesAgentInviteGateway;
import com.innbucks.eventservice.client.ScannerInviteGateway;
import com.innbucks.eventservice.dto.OrganizerDTO;
import com.innbucks.eventservice.dto.scanner.*;
import com.innbucks.eventservice.entity.Event;
import com.innbucks.eventservice.entity.EventSalesAssignment;
import com.innbucks.eventservice.entity.EventScanner;
import com.innbucks.eventservice.entity.EventScannerAssignment;
import com.innbucks.eventservice.exception.ApiProblemException;
import com.innbucks.eventservice.exception.FieldErrorsException;
import com.innbucks.eventservice.repository.EventRepository;
import com.innbucks.eventservice.repository.EventSalesAssignmentRepository;
import com.innbucks.eventservice.repository.EventScannerAssignmentRepository;
import com.innbucks.eventservice.repository.EventScannerRepository;
import com.innbucks.eventservice.util.HtmlSanitizer;
import com.innbucks.eventservice.util.MsisdnValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * An organizer's event team (V14, V21): an organizer names a phone number, the
 * person behind it accepts in the super app, and that account then scans the
 * events it is assigned to, sells tickets for the events it sells for, or both.
 *
 * <p><b>One rule spans organizers: a number never SCANS two events whose times
 * overlap</b> (owner decision, 2026-10-30; organizers may run overlapping
 * events, the person scanning may not be at both). It is checked whenever a
 * row starts counting toward the number's schedule (assign, enable, invite
 * again, given the Scan role back), under a lock on every row of the number,
 * against the events' live times. It is NOT re-checked when an organizer moves
 * their own event: one organizer's schedule change is never blocked by
 * another's staffing. The refusal names neither the other event nor its
 * organizer. Selling has no such rule (owner decision, 2026-11-06).
 *
 * <p><b>Roles (V21).</b> A row scans ({@code can_scan}), sells
 * ({@code can_sell}: a sales agent), or both, never neither. Taking a role
 * away keeps its assignments, but they then grant nothing, and a row that does
 * not scan does not count toward the number's schedule.
 *
 * <p><b>Nothing here says whether a number has an InnBucks account.</b> Every
 * new team member is INVITED; only the person, signed in on that number, can
 * accept. Accepting binds the row to the account that accepted, and only that
 * account scans or sells for it, so a recycled number's new owner inherits
 * nothing.
 *
 * <p>The super-app endpoints take the phone and the account from the caller's
 * session, never from a request.
 *
 * <p><b>The person is told (V20, owner's decision 2026-11-05; roles V21).</b>
 * When an INVITED row first has an event to accept (its first assignment of
 * either kind, being invited again after declining, being switched back on),
 * user-service sends one WhatsApp, or an SMS when WhatsApp fails, naming the
 * organizer, the event and the roles, and saying to open the InnBucks app.
 * {@code invite_notified_at} makes it once: a conditional UPDATE claims it, so
 * two assignments at once send one message, and inviting again clears it. The
 * write commits first and the message goes after, with no connection held; a
 * failed send never fails the assignment. The organizer still never learns
 * whether the number has an account, or whether the message arrived.
 *
 * <p><b>Portal access (V21).</b> A sales agent can also sell from the Foundry
 * portal: the organizer gives an email, user-service sends the invite and owns
 * the account. That call runs with no transaction open; only its success is
 * recorded here.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ScannerService {

    public static final String SCANNER_NOT_FOUND = "scanner_not_found";
    public static final String EVENT_NOT_FOUND = "event_not_found";
    public static final String INVALID_PHONE = "invalid_phone";
    public static final String ALREADY_ON_TEAM = "scanner_already_on_team";
    public static final String SCHEDULE_CLASH = "scanner_schedule_clash";
    public static final String EVENT_ENDED = "event_ended";
    public static final String NOT_DECLINED = "scanner_not_declined";
    public static final String LIMIT_REACHED = "scanner_limit_reached";
    public static final String PHONE_REQUIRED = "phone_required";
    public static final String INVITE_NOT_FOUND = "invite_not_found";
    public static final String INVITE_NOT_PENDING = "invite_not_pending";
    public static final String NO_ROLE = "no_role";
    public static final String NOT_A_SCANNER = "not_a_scanner";
    public static final String NOT_A_SALES_AGENT = "not_a_sales_agent";
    public static final String SCANNER_DISABLED = "scanner_disabled";
    public static final String SCANNER_DECLINED = "scanner_declined";
    public static final String INVALID_EMAIL = "invalid_email";
    public static final String PORTAL_INVITE_UNAVAILABLE = "portal_invite_unavailable";
    public static final String ACCOUNT_REQUIRED = "account_required";
    public static final String ORGANIZER_REQUIRED = "organizer_required";
    public static final String ORGANIZER_NOT_FOUND = "organizer_not_found";
    public static final String SALES_AGENT_ORGANIZER_ONLY = "sales_agent_organizer_only";
    public static final String SALES_AGENT_ORGANIZER_ONLY_MESSAGE =
            "Only the organizer can manage a sales agent. InnBucks staff can manage gate scanners only.";

    /**
     * Who is changing a team. The organizer edits their own; InnBucks staff
     * (SUPER_ADMIN, PRODUCT_MANAGER; owner's decision 2026-11-13) edit an
     * organizer's GATE SCANNERS only: add, assign to the organizer's events,
     * unassign, switch off and on, invite again. A member who sells is the
     * organizer's alone, and so are roles, sales terms and portal access.
     *
     * <p>For staff, {@code organizerUuid} is the organizer they named: required
     * to add, and optional on a member's own actions, where the member's own
     * organizer is used (a named one that is not the member's is a 404).
     */
    public record TeamEditor(UUID organizerUuid, String actor, boolean staff) {
        public static TeamEditor organizer(UUID organizerUuid, String actor) {
            return new TeamEditor(organizerUuid, actor, false);
        }

        public static TeamEditor innbucksStaff(UUID organizerUuidOrNull, String actor) {
            return new TeamEditor(organizerUuidOrNull, actor, true);
        }
    }

    static final String SCHEDULE_CLASH_MESSAGE = "This number is already assigned to another event at that time.";
    static final String NO_ROLE_MESSAGE = "Choose at least one: scan tickets or sell tickets.";
    static final String NOT_A_SCANNER_MESSAGE =
            "This person doesn't scan tickets for you. Give them the Scan role first.";
    static final String NOT_A_SALES_AGENT_MESSAGE =
            "This person doesn't sell tickets for you. Give them the Sell role first.";
    static final String PORTAL_INVITE_UNAVAILABLE_MESSAGE =
            "We couldn't send the portal invite just now. Please try again.";

    static final int MAX_EMAIL_LENGTH = 254;
    private static final Pattern EMAIL_SHAPE = Pattern.compile("[^@\\s]+@[^@\\s]+");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final Comparator<Event> SOONEST_FIRST =
            Comparator.comparing(Event::getStartDateTime).thenComparing(Event::getEventId);

    private final EventScannerRepository scannerRepository;
    private final EventScannerAssignmentRepository assignmentRepository;
    private final EventRepository eventRepository;
    private final OrganizerGateway organizerGateway;
    private final EventSalesAssignmentRepository salesAssignmentRepository;

    /** The cell's country: how a number typed without a country code is read. */
    @Value("${innbucks.country:ZW}")
    private String deploymentCountry = "ZW";

    /**
     * Most team members one organizer may name. A guard, not a quota: each one
     * puts an invite in that number's super app, so an unbounded list is a way
     * to fill strangers' apps.
     */
    @Value("${events.scanners.max-per-organizer:500}")
    private int maxPerOrganizer = 500;

    private Clock clock = Clock.systemUTC();

    void setClock(Clock clock) {
        this.clock = clock;
    }

    void setMaxPerOrganizer(int maxPerOrganizer) {
        this.maxPerOrganizer = maxPerOrganizer;
    }

    /** Sends the invite message (V20); absent in hand-built tests, which then send nothing. */
    private ScannerInviteGateway inviteGateway;

    /** Sends and revokes portal invites (V21); absent in hand-built tests, where a portal invite is a 503. */
    private SalesAgentInviteGateway portalInviteGateway;

    /**
     * The write phase of assign, invite-again, enable and the portal invite,
     * so a message or a remote call goes after it commits (or before it
     * starts) with no connection held. Absent in hand-built tests, which run
     * the phase inline.
     */
    private TransactionTemplate transactionTemplate;

    @Autowired(required = false)
    public void setScannerInviteGateway(ScannerInviteGateway inviteGateway) {
        this.inviteGateway = inviteGateway;
    }

    @Autowired(required = false)
    public void setSalesAgentInviteGateway(SalesAgentInviteGateway portalInviteGateway) {
        this.portalInviteGateway = portalInviteGateway;
    }

    @Autowired(required = false)
    public void setTransactionManager(PlatformTransactionManager transactionManager) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** What a write phase hands back: the answer, and the invite message to send once it commits. */
    private record Written(ScannerResponseDTO response, InviteNotice invite) {}

    /** One invite message: whose number, which organizer's team, the event it names, and the roles. */
    private record InviteNotice(UUID scannerId, UUID organizerUuid, String phoneNumber, String eventTitle,
                                List<String> roles) {}

    /** One event a row sells for, with the organizer's terms. */
    private record Sale(EventSalesAssignment terms, Event event) {}

    // ---------------------------------------------------------------- organizer

    @Transactional
    public ScannerResponseDTO add(UUID organizerUuid, String actor, CreateScannerRequestDTO request) {
        return add(TeamEditor.organizer(organizerUuid, actor), request);
    }

    @Transactional
    public ScannerResponseDTO add(TeamEditor editor, CreateScannerRequestDTO request) {
        boolean canScan = request.canScan() == null || request.canScan();
        boolean canSell = Boolean.TRUE.equals(request.canSell());
        requireARole(canScan, canSell);
        UUID organizerUuid = editor.organizerUuid();
        if (editor.staff()) {
            if (canSell) {
                throw salesAgentOrganizerOnly();
            }
            if (organizerUuid == null) {
                throw new ApiProblemException(HttpStatus.BAD_REQUEST, ORGANIZER_REQUIRED,
                        "Choose the organizer whose team this is.");
            }
            // Local and live: an id with no event is a typo, or not an organizer,
            // and a team row under it could never be assigned to anything.
            if (!eventRepository.existsByTenantUserUuidAndDeletedFalse(organizerUuid)) {
                throw new ApiProblemException(HttpStatus.NOT_FOUND, ORGANIZER_NOT_FOUND,
                        "That organizer has no events. Check the organizer and try again.");
            }
        }
        String phone = MsisdnValidator.normalizeToE164(request.phoneNumber(), deploymentCountry)
                .orElseThrow(() -> new ApiProblemException(HttpStatus.BAD_REQUEST, INVALID_PHONE,
                        "That isn't a valid mobile number.", Map.of("field", "phoneNumber")));
        Optional<EventScanner> existing = scannerRepository.findByOrganizerUuidAndPhoneNumber(organizerUuid, phone);
        if (existing.isPresent()) {
            throw alreadyOnTeam(existing.get(), editor.staff());
        }
        if (scannerRepository.countByOrganizerUuid(organizerUuid) >= maxPerOrganizer) {
            throw new ApiProblemException(HttpStatus.CONFLICT, LIMIT_REACHED,
                    (editor.staff() ? "This organizer's team" : "Your team") + " already has " + maxPerOrganizer
                            + " members. Remove one before adding another.",
                    Map.of("limit", maxPerOrganizer));
        }
        EventScanner scanner = EventScanner.builder()
                .scannerId(UUID.randomUUID())
                .organizerUuid(organizerUuid)
                .phoneNumber(phone)
                .firstName(cleanName(request.firstName()))
                .lastName(cleanName(request.lastName()))
                .canScan(canScan)
                .canSell(canSell)
                .createdBy(editor.actor())
                .addedByStaff(editor.staff())
                .build();
        try {
            scannerRepository.saveAndFlush(scanner);
        } catch (DataIntegrityViolationException race) {
            // Two adds of one number at once: the unique key let one in.
            throw scannerRepository.findByOrganizerUuidAndPhoneNumber(organizerUuid, phone)
                    .map(s -> alreadyOnTeam(s, editor.staff()))
                    .orElseThrow(() -> race);
        }
        log.info("Scanner added scannerId={} organizerUuid={} canScan={} canSell={} byStaff={} by={}",
                scanner.getScannerId(), organizerUuid, canScan, canSell, editor.staff(), editor.actor());
        return toResponse(scanner, List.of(), List.of());
    }

    /** {@code organizerUuid} null = platform staff reading every organizer's. */
    @Transactional(readOnly = true)
    public List<ScannerResponseDTO> list(UUID organizerUuid) {
        List<EventScanner> scanners = organizerUuid == null
                ? scannerRepository.findAllByOrderByCreatedAtAscScannerIdAsc()
                : scannerRepository.findByOrganizerUuidOrderByCreatedAtAscScannerIdAsc(organizerUuid);
        return toResponses(scanners);
    }

    /** {@code organizerUuid} null = platform staff, who read any organizer's. */
    @Transactional(readOnly = true)
    public ScannerResponseDTO get(UUID organizerUuid, UUID scannerId) {
        EventScanner scanner = organizerUuid == null
                ? scannerRepository.findById(scannerId).orElseThrow(this::scannerNotFound)
                : owned(organizerUuid, scannerId);
        return toResponses(List.of(scanner)).get(0);
    }

    public ScannerResponseDTO assign(UUID organizerUuid, String actor, UUID scannerId, UUID eventId) {
        return assign(TeamEditor.organizer(organizerUuid, actor), scannerId, eventId);
    }

    public ScannerResponseDTO assign(TeamEditor editor, UUID scannerId, UUID eventId) {
        return afterCommit(write(() -> assignInWrite(editor, scannerId, eventId)));
    }

    private Written assignInWrite(TeamEditor editor, UUID scannerId, UUID eventId) {
        EventScanner scanner = target(editor, scannerId);
        UUID organizerUuid = scanner.getOrganizerUuid();
        String actor = editor.actor();
        if (!scanner.isCanScan()) {
            throw new ApiProblemException(HttpStatus.CONFLICT, NOT_A_SCANNER, NOT_A_SCANNER_MESSAGE);
        }
        LocalDateTime now = now();
        Event event = ownedLiveEvent(organizerUuid, eventId, now);
        // Every row of the number, first: the rule spans organizers, so a
        // concurrent assignment of the same number elsewhere waits here. The
        // already-assigned check comes after it, so two identical requests at
        // once answer the second from the first's committed row.
        scannerRepository.lockAllByPhoneNumber(scanner.getPhoneNumber());
        if (assignmentRepository.existsByScannerIdAndEventId(scannerId, eventId)) {
            return new Written(get(organizerUuid, scannerId), null);
        }
        long clashes = assignmentRepository.countClashes(scanner.getPhoneNumber(), scannerId, eventId,
                event.getStartDateTime(), event.getEndDateTime(), now);
        if (clashes > 0) {
            log.info("Scanner assignment refused, schedule clash scannerId={} eventId={} by={}",
                    scannerId, eventId, actor);
            throw scheduleClash();
        }
        assignmentRepository.save(EventScannerAssignment.builder()
                .assignmentId(UUID.randomUUID())
                .scannerId(scannerId)
                .eventId(eventId)
                .assignedBy(actor)
                .build());
        log.info("Scanner assigned scannerId={} eventId={} by={}", scannerId, eventId, actor);
        return new Written(get(organizerUuid, scannerId), claimInvite(scanner, event, now));
    }

    @Transactional
    public ScannerResponseDTO unassign(UUID organizerUuid, String actor, UUID scannerId, UUID eventId) {
        return unassign(TeamEditor.organizer(organizerUuid, actor), scannerId, eventId);
    }

    @Transactional
    public ScannerResponseDTO unassign(TeamEditor editor, UUID scannerId, UUID eventId) {
        EventScanner scanner = target(editor, scannerId);
        if (assignmentRepository.deleteByScannerIdAndEventId(scannerId, eventId) > 0) {
            log.info("Scanner unassigned scannerId={} eventId={} byStaff={} by={}",
                    scannerId, eventId, editor.staff(), editor.actor());
        }
        return get(scanner.getOrganizerUuid(), scannerId);
    }

    /** Switched off: the next scan and the next sale are refused; the record and its assignments stay. */
    @Transactional
    public ScannerResponseDTO disable(UUID organizerUuid, String actor, UUID scannerId) {
        return disable(TeamEditor.organizer(organizerUuid, actor), scannerId);
    }

    @Transactional
    public ScannerResponseDTO disable(TeamEditor editor, UUID scannerId) {
        EventScanner scanner = target(editor, scannerId);
        if (scanner.getDisabledAt() == null) {
            scanner.setDisabledAt(now());
            scannerRepository.save(scanner);
            log.info("Scanner disabled scannerId={} byStaff={} by={}", scannerId, editor.staff(), editor.actor());
        }
        return get(scanner.getOrganizerUuid(), scannerId);
    }

    public ScannerResponseDTO enable(UUID organizerUuid, String actor, UUID scannerId) {
        return enable(TeamEditor.organizer(organizerUuid, actor), scannerId);
    }

    public ScannerResponseDTO enable(TeamEditor editor, UUID scannerId) {
        return afterCommit(write(() -> {
            EventScanner scanner = target(editor, scannerId);
            UUID organizerUuid = scanner.getOrganizerUuid();
            String actor = editor.actor();
            InviteNotice invite = null;
            if (scanner.getDisabledAt() != null) {
                // Back on, its scanning events count again: they must not
                // overlap what the number took on elsewhere while this row was off.
                if (scanner.isCanScan()) {
                    requireNoClashOnReturn(scanner);
                }
                scanner.setDisabledAt(null);
                scannerRepository.save(scanner);
                log.info("Scanner enabled scannerId={} by={}", scannerId, actor);
                invite = claimInviteForNextEvent(scanner);
            }
            return new Written(get(organizerUuid, scannerId), invite);
        }));
    }

    /** A DECLINED team member (declined, or left) back to INVITED. */
    public ScannerResponseDTO reinvite(UUID organizerUuid, String actor, UUID scannerId) {
        return reinvite(TeamEditor.organizer(organizerUuid, actor), scannerId);
    }

    public ScannerResponseDTO reinvite(TeamEditor editor, UUID scannerId) {
        return afterCommit(write(() -> {
            EventScanner scanner = target(editor, scannerId);
            UUID organizerUuid = scanner.getOrganizerUuid();
            String actor = editor.actor();
            if (scanner.getDeclinedAt() == null) {
                throw new ApiProblemException(HttpStatus.CONFLICT, NOT_DECLINED,
                        "Only a scanner who declined can be invited again.");
            }
            if (scanner.getDisabledAt() == null && scanner.isCanScan()) {
                requireNoClashOnReturn(scanner);
            }
            scanner.setDeclinedAt(null);
            // A new invite: the person hears about it again.
            scanner.setInviteNotifiedAt(null);
            scannerRepository.save(scanner);
            log.info("Scanner invited again scannerId={} by={}", scannerId, actor);
            return new Written(get(organizerUuid, scannerId), claimInviteForNextEvent(scanner));
        }));
    }

    /**
     * Scan, sell, or both (V21). Giving the Scan role back to a row that counts
     * (not switched off, not declined) is checked like switching it back on:
     * its scanning events start counting again. Taking a role away keeps the
     * assignments; they grant nothing until the role is back. Sends nothing.
     */
    @Transactional
    public ScannerResponseDTO setRoles(UUID organizerUuid, String actor, UUID scannerId,
                                       boolean canScan, boolean canSell) {
        requireARole(canScan, canSell);
        EventScanner scanner = owned(organizerUuid, scannerId);
        if (scanner.isCanScan() != canScan || scanner.isCanSell() != canSell) {
            if (canScan && !scanner.isCanScan()
                    && scanner.getDisabledAt() == null && scanner.getDeclinedAt() == null) {
                requireNoClashOnReturn(scanner);
            }
            scanner.setCanScan(canScan);
            scanner.setCanSell(canSell);
            scannerRepository.save(scanner);
            log.info("Scanner roles changed scannerId={} canScan={} canSell={} by={}",
                    scannerId, canScan, canSell, actor);
        }
        return get(organizerUuid, scannerId);
    }

    /**
     * Assigns a sales agent to one of the organizer's events, or changes the
     * terms of an assignment it has (V21). No overlap rule: selling has none.
     * The first assignment of either kind of an INVITED row sends the invite
     * message, after the commit.
     */
    public ScannerResponseDTO assignSalesEvent(UUID organizerUuid, String actor, UUID scannerId, UUID eventId,
                                               Integer ticketCap, BigDecimal commissionPercent) {
        BigDecimal commission = requireValidTerms(ticketCap, commissionPercent);
        return afterCommit(write(() -> {
            // This row, locked: the upsert reads then writes, so two at once queue.
            EventScanner scanner = scannerRepository.lockByScannerIdAndOrganizerUuid(scannerId, organizerUuid)
                    .orElseThrow(this::scannerNotFound);
            if (!scanner.isCanSell()) {
                throw notASalesAgent();
            }
            LocalDateTime now = now();
            Event event = ownedLiveEvent(organizerUuid, eventId, now);
            Optional<EventSalesAssignment> existing =
                    salesAssignmentRepository.findByScannerIdAndEventId(scannerId, eventId);
            InviteNotice invite = null;
            if (existing.isPresent()) {
                EventSalesAssignment terms = existing.get();
                if (!Objects.equals(terms.getTicketCap(), ticketCap)
                        || terms.getCommissionPercent() == null
                        || terms.getCommissionPercent().compareTo(commission) != 0) {
                    terms.setTicketCap(ticketCap);
                    terms.setCommissionPercent(commission);
                    salesAssignmentRepository.save(terms);
                    log.info("Sales event terms changed scannerId={} eventId={} ticketCap={} commission={} by={}",
                            scannerId, eventId, ticketCap, commission, actor);
                }
            } else {
                salesAssignmentRepository.save(EventSalesAssignment.builder()
                        .assignmentId(UUID.randomUUID())
                        .scannerId(scannerId)
                        .eventId(eventId)
                        .ticketCap(ticketCap)
                        .commissionPercent(commission)
                        .assignedBy(actor)
                        .build());
                log.info("Sales event assigned scannerId={} eventId={} ticketCap={} commission={} by={}",
                        scannerId, eventId, ticketCap, commission, actor);
                invite = claimInvite(scanner, event, now);
            }
            return new Written(get(organizerUuid, scannerId), invite);
        }));
    }

    @Transactional
    public ScannerResponseDTO unassignSalesEvent(UUID organizerUuid, String actor, UUID scannerId, UUID eventId) {
        owned(organizerUuid, scannerId);
        if (salesAssignmentRepository.deleteByScannerIdAndEventId(scannerId, eventId) > 0) {
            log.info("Sales event unassigned scannerId={} eventId={} by={}", scannerId, eventId, actor);
        }
        return get(organizerUuid, scannerId);
    }

    /**
     * Sends a sales agent a Foundry portal invite (V21) through user-service.
     * Not transactional, on purpose: the row is read, user-service is asked
     * with no transaction open, and only a sent invite is recorded, in its own
     * short write. A user-service refusal (400) comes back with its own
     * {@code errorCode} and words; no answer is a 503.
     */
    public ScannerResponseDTO grantPortalAccess(UUID organizerUuid, String actor, UUID scannerId, String rawEmail) {
        String email = cleanEmail(rawEmail);
        EventScanner scanner = owned(organizerUuid, scannerId);
        if (!scanner.isCanSell()) {
            throw notASalesAgent();
        }
        if (scanner.getDisabledAt() != null) {
            throw new ApiProblemException(HttpStatus.CONFLICT, SCANNER_DISABLED,
                    "This person is switched off. Switch them back on first.");
        }
        if (scanner.getDeclinedAt() != null) {
            throw new ApiProblemException(HttpStatus.CONFLICT, SCANNER_DECLINED,
                    "This person declined your invite. Invite them again first.");
        }
        if (portalInviteGateway == null) {
            throw portalInviteUnavailable();
        }
        SalesAgentInviteGateway.Outcome outcome = portalInviteGateway.invite(scannerId, organizerUuid,
                organizerNameOrNull(organizerUuid, scannerId), scanner.getPhoneNumber(), email);
        switch (outcome) {
            case SalesAgentInviteGateway.Refused refused ->
                    throw new ApiProblemException(HttpStatus.BAD_REQUEST, refused.errorCode(), refused.message(),
                            refused.field() == null ? Map.of() : Map.of("field", refused.field()));
            case SalesAgentInviteGateway.Unavailable unavailable -> {
                log.warn("Portal invite unavailable scannerId={} reason={}", scannerId, unavailable.reason());
                throw portalInviteUnavailable();
            }
            case SalesAgentInviteGateway.Sent sent -> {
                // Recorded below.
            }
        }
        return write(() -> {
            EventScanner current = owned(organizerUuid, scannerId);
            current.setPortalEmail(email);
            current.setPortalInvitedAt(now());
            scannerRepository.save(current);
            log.info("Portal invite recorded scannerId={} by={}", scannerId, actor);
            return get(organizerUuid, scannerId);
        });
    }

    /**
     * Clears the portal invite record, then asks user-service to revoke the
     * agent's open invites, best-effort. Never removes an account's role or
     * password: an agent who already signed in is stopped by the live seller
     * check (switch them off, or take Sell away). Idempotent.
     */
    public ScannerResponseDTO revokePortalAccess(UUID organizerUuid, String actor, UUID scannerId) {
        ScannerResponseDTO response = write(() -> {
            EventScanner scanner = owned(organizerUuid, scannerId);
            if (scanner.getPortalEmail() != null || scanner.getPortalInvitedAt() != null) {
                scanner.setPortalEmail(null);
                scanner.setPortalInvitedAt(null);
                scannerRepository.save(scanner);
                log.info("Portal access removed scannerId={} by={}", scannerId, actor);
            }
            return get(organizerUuid, scannerId);
        });
        if (portalInviteGateway != null) {
            portalInviteGateway.revoke(scannerId);
        }
        return response;
    }

    // ----------------------------------------------------------------- super app

    /**
     * The signed-in person's scanning events and pending invites. Not
     * transactional: the reads are independent, and the organizer names are a
     * remote call (cached, fail-safe: a miss renders the name null).
     */
    public GateResponseDTO gate(UUID accountUserUuid, String sessionPhone) {
        String phone = requirePhone(sessionPhone);
        LocalDateTime now = now();

        List<EventScanner> scanning = accountUserUuid == null ? List.of()
                : scannerRepository.findActiveForAccount(accountUserUuid).stream()
                        .filter(EventScanner::isCanScan).toList();
        List<EventScanner> pending = scannerRepository.findPendingForPhone(phone);
        if (scanning.isEmpty() && pending.isEmpty()) {
            return new GateResponseDTO(List.of(), List.of());
        }

        Map<UUID, List<Event>> upcoming = upcomingEventsByScanner(
                concat(scanning, pending.stream().filter(EventScanner::isCanScan).toList()), now);
        Map<UUID, List<Sale>> upcomingSales = upcomingSalesByScanner(
                pending.stream().filter(EventScanner::isCanSell).toList(), now);
        Set<UUID> organizers = new LinkedHashSet<>();
        scanning.forEach(s -> organizers.add(s.getOrganizerUuid()));
        pending.forEach(s -> organizers.add(s.getOrganizerUuid()));
        Map<UUID, OrganizerDTO> names = organizerGateway.organizersByUserUuids(organizers);

        List<GateEventDTO> events = new ArrayList<>();
        for (EventScanner scanner : scanning) {
            for (Event event : upcoming.getOrDefault(scanner.getScannerId(), List.of())) {
                boolean live = !event.getStartDateTime().isAfter(now);
                events.add(new GateEventDTO(scanner.getScannerId(), event.getEventId(), event.getTitle(),
                        event.getVenue(), event.getStartDateTime(), event.getEndDateTime(),
                        live ? "LIVE" : "UPCOMING", organizerName(names, scanner.getOrganizerUuid())));
            }
        }
        events.sort(Comparator.comparing(GateEventDTO::startDateTime).thenComparing(GateEventDTO::eventId));

        List<GateInviteDTO> invites = pending.stream()
                .map(s -> new GateInviteDTO(s.getScannerId(), organizerName(names, s.getOrganizerUuid()),
                        s.displayName(), s.getCreatedAt(),
                        !s.isCanScan() ? List.of() : upcoming.getOrDefault(s.getScannerId(), List.of()).stream()
                                .map(ScannerService::eventView).toList(),
                        s.isCanScan(), s.isCanSell(),
                        !s.isCanSell() ? List.of() : upcomingSales.getOrDefault(s.getScannerId(), List.of())
                                .stream().map(sale -> eventView(sale.event())).toList()))
                .toList();
        return new GateResponseDTO(events, invites);
    }

    /**
     * The signed-in sales agent's selling events (V21): every event of a team
     * they accepted, are still on, and sell for, that is not deleted and has
     * not ended, soonest first. The account is the session's; no phone is
     * needed, so a Foundry portal session works as well as the app's. Display
     * only: every sale asks again, live.
     */
    public AgentSalesResponseDTO sales(UUID accountUserUuid) {
        if (accountUserUuid == null) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, ACCOUNT_REQUIRED,
                    "Sign in again to see the events you sell for.");
        }
        List<EventScanner> sellers = scannerRepository.findActiveSellersForAccount(accountUserUuid).stream()
                .filter(s -> sellsFor(s, accountUserUuid))
                .toList();
        Map<UUID, List<Sale>> upcoming = upcomingSalesByScanner(sellers, now());
        if (upcoming.isEmpty()) {
            return new AgentSalesResponseDTO(List.of());
        }
        Map<UUID, OrganizerDTO> names = organizerGateway.organizersByUserUuids(sellers.stream()
                .filter(s -> upcoming.containsKey(s.getScannerId()))
                .map(EventScanner::getOrganizerUuid)
                .collect(Collectors.toCollection(LinkedHashSet::new)));
        List<AgentSalesEventDTO> events = new ArrayList<>();
        for (EventScanner seller : sellers) {
            for (Sale sale : upcoming.getOrDefault(seller.getScannerId(), List.of())) {
                Event event = sale.event();
                events.add(new AgentSalesEventDTO(seller.getScannerId(), event.getEventId(), event.getTitle(),
                        event.getVenue(), event.getStartDateTime(), event.getEndDateTime(),
                        organizerName(names, seller.getOrganizerUuid()),
                        sale.terms().getTicketCap(), sale.terms().getCommissionPercent()));
            }
        }
        events.sort(Comparator.comparing(AgentSalesEventDTO::startDateTime)
                .thenComparing(AgentSalesEventDTO::eventId)
                .thenComparing(AgentSalesEventDTO::agentId));
        return new AgentSalesResponseDTO(events);
    }

    @Transactional
    public GateInviteDecisionDTO accept(UUID accountUserUuid, String sessionPhone, UUID scannerId) {
        String phone = requirePhone(sessionPhone);
        EventScanner scanner = forThisNumber(scannerId, phone);
        switch (scanner.status()) {
            case ACTIVE -> {
                if (!scanner.getAccountUserUuid().equals(accountUserUuid)) {
                    throw new ApiProblemException(HttpStatus.CONFLICT, INVITE_NOT_PENDING,
                            "This invite has already been accepted.");
                }
            }
            case DECLINED -> throw new ApiProblemException(HttpStatus.CONFLICT, INVITE_NOT_PENDING,
                    "You declined this invite. Ask the organizer to invite you again.");
            default -> {
                if (accountUserUuid == null) {
                    throw inviteNotFound();
                }
                scanner.setAcceptedAt(now());
                scanner.setAccountUserUuid(accountUserUuid);
                scannerRepository.save(scanner);
                log.info("Scanner invite accepted scannerId={} account={}", scannerId, accountUserUuid);
            }
        }
        return decision(scanner);
    }

    /** Declines an invite, or leaves an accepted team. */
    @Transactional
    public GateInviteDecisionDTO decline(UUID accountUserUuid, String sessionPhone, UUID scannerId) {
        String phone = requirePhone(sessionPhone);
        EventScanner scanner = forThisNumber(scannerId, phone);
        if (scanner.status() == EventScanner.Status.ACTIVE
                && !scanner.getAccountUserUuid().equals(accountUserUuid)) {
            // The number is the session's, the team another account's: a
            // recycled number. Nothing of it is this person's to leave.
            throw inviteNotFound();
        }
        if (scanner.getDeclinedAt() == null) {
            scanner.setDeclinedAt(now());
            scanner.setAcceptedAt(null);
            scanner.setAccountUserUuid(null);
            scannerRepository.save(scanner);
            log.info("Scanner invite declined scannerId={} account={}", scannerId, accountUserUuid);
        }
        return decision(scanner);
    }

    // ------------------------------------------------------------------ internal

    /**
     * booking-service, before it reads a ticket: is this account an accepted,
     * switched-on scanner (V21: one that scans) assigned to this event, on that
     * event's organizer's team? Read live on every scan, so a removal bites at
     * the next scan.
     */
    @Transactional(readOnly = true)
    public ScannerAccessDTO access(UUID accountUserUuid, UUID eventId) {
        if (accountUserUuid == null || eventId == null) {
            return ScannerAccessDTO.denied();
        }
        Optional<Event> event = eventRepository.findById(eventId).filter(e -> !e.isDeleted());
        if (event.isEmpty()) {
            return ScannerAccessDTO.denied();
        }
        for (EventScanner scanner : scannerRepository.findActiveForAccount(accountUserUuid)) {
            if (scanner.isCanScan()
                    && scanner.getOrganizerUuid().equals(event.get().getTenantUserUuid())
                    && assignmentRepository.existsByScannerIdAndEventId(scanner.getScannerId(), eventId)) {
                return new ScannerAccessDTO(true, scanner.getScannerId(), scanner.getOrganizerUuid(),
                        scanner.displayName());
            }
        }
        return ScannerAccessDTO.denied();
    }

    /**
     * booking-service, before every agent sale (V21): may this account sell
     * tickets for this event, and on what terms? Allowed only for a row the
     * account accepted, still on (not declined, not switched off), that sells,
     * on the team of the event's organizer, assigned to the event, which is not
     * deleted. Read live on every sale, never cached, so a removal (switched
     * off, Sell taken away, unassigned) stops the next sale on both surfaces.
     * The query narrows; every condition is checked again here, so a widened
     * query can never grant.
     */
    @Transactional(readOnly = true)
    public SellerAccessDTO sellerAccess(UUID accountUserUuid, UUID eventId) {
        if (accountUserUuid == null || eventId == null) {
            return SellerAccessDTO.denied();
        }
        Optional<Event> event = eventRepository.findById(eventId).filter(e -> !e.isDeleted());
        UUID organizer = event.map(Event::getTenantUserUuid).orElse(null);
        if (organizer == null) {
            return SellerAccessDTO.denied();
        }
        for (EventScanner seller : scannerRepository.findActiveSellersForAccount(accountUserUuid)) {
            if (!sellsFor(seller, accountUserUuid) || !organizer.equals(seller.getOrganizerUuid())) {
                continue;
            }
            Optional<EventSalesAssignment> terms =
                    salesAssignmentRepository.findByScannerIdAndEventId(seller.getScannerId(), eventId);
            if (terms.isPresent()) {
                return new SellerAccessDTO(true, seller.getScannerId(), seller.getOrganizerUuid(),
                        seller.displayName(), terms.get().getTicketCap(), terms.get().getCommissionPercent());
            }
        }
        return SellerAccessDTO.denied();
    }

    // ------------------------------------------------------------ invite message

    private <T> T write(Supplier<T> work) {
        return transactionTemplate == null ? work.get() : transactionTemplate.execute(status -> work.get());
    }

    /**
     * Claims the invite message for {@code scanner}, naming {@code event}, if it
     * is INVITED and has not had one since it was (re)invited. Null when it is
     * not this call's to send. The roles are the row's as they stand now.
     */
    private InviteNotice claimInvite(EventScanner scanner, Event event, LocalDateTime now) {
        if (scannerRepository.claimInviteNotice(scanner.getScannerId(), now) != 1) {
            return null;
        }
        return new InviteNotice(scanner.getScannerId(), scanner.getOrganizerUuid(), scanner.getPhoneNumber(),
                event.getTitle(), scanner.roles());
    }

    /**
     * As {@link #claimInvite}, naming the row's soonest assigned event that has
     * not ended, among the kinds it does (scanning, selling); none, no message.
     */
    private InviteNotice claimInviteForNextEvent(EventScanner scanner) {
        LocalDateTime now = now();
        List<Event> candidates = new ArrayList<>();
        if (scanner.isCanScan()) {
            candidates.addAll(upcomingEventsByScanner(List.of(scanner), now)
                    .getOrDefault(scanner.getScannerId(), List.of()));
        }
        if (scanner.isCanSell()) {
            upcomingSalesByScanner(List.of(scanner), now).getOrDefault(scanner.getScannerId(), List.of())
                    .forEach(sale -> candidates.add(sale.event()));
        }
        candidates.sort(SOONEST_FIRST);
        return candidates.isEmpty() ? null : claimInvite(scanner, candidates.get(0), now);
    }

    /**
     * After the write phase has committed: the organizer's name (a cached remote
     * read) and the request to user-service, both best-effort and with no
     * transaction open.
     */
    private ScannerResponseDTO afterCommit(Written written) {
        InviteNotice invite = written.invite();
        if (invite != null && inviteGateway != null) {
            inviteGateway.sendInvite(invite.scannerId(), invite.phoneNumber(),
                    organizerNameOrNull(invite.organizerUuid(), invite.scannerId()), invite.eventTitle(),
                    invite.roles());
            log.info("Scanner invite message requested scannerId={} roles={}", invite.scannerId(), invite.roles());
        }
        return written.response();
    }

    /** The organizer's business name for a message, or null when it cannot be had. Never throws. */
    private String organizerNameOrNull(UUID organizerUuid, UUID scannerId) {
        try {
            return organizerName(organizerGateway.organizersByUserUuids(List.of(organizerUuid)), organizerUuid);
        } catch (RuntimeException e) {
            log.warn("Organizer name unavailable scannerId={} cause={}", scannerId, e.toString());
            return null;
        }
    }

    // ------------------------------------------------------------------- helpers

    private EventScanner owned(UUID organizerUuid, UUID scannerId) {
        return scannerRepository.findByScannerIdAndOrganizerUuid(scannerId, organizerUuid)
                .orElseThrow(this::scannerNotFound);
    }

    /**
     * The member a team action is for. The organizer reaches their own only.
     * Staff reach any organizer's member (the one named, when they name one),
     * but never a member who sells: a sales agent is the organizer's alone,
     * even when the action itself is about scanning. Checked in the action's
     * own transaction.
     */
    private EventScanner target(TeamEditor editor, UUID scannerId) {
        if (!editor.staff()) {
            return owned(editor.organizerUuid(), scannerId);
        }
        EventScanner scanner = scannerRepository.findById(scannerId)
                .filter(s -> editor.organizerUuid() == null || editor.organizerUuid().equals(s.getOrganizerUuid()))
                .orElseThrow(this::scannerNotFound);
        if (scanner.isCanSell()) {
            log.info("Staff team action refused, member sells scannerId={} by={}", scannerId, editor.actor());
            throw salesAgentOrganizerOnly();
        }
        return scanner;
    }

    /** The organizer's event, not deleted and not ended: 404 otherwise, or 409 when it has ended. */
    private Event ownedLiveEvent(UUID organizerUuid, UUID eventId, LocalDateTime now) {
        Event event = eventRepository.findById(eventId)
                .filter(e -> !e.isDeleted() && organizerUuid.equals(e.getTenantUserUuid()))
                .orElseThrow(() -> new ApiProblemException(HttpStatus.NOT_FOUND, EVENT_NOT_FOUND,
                        "Event not found."));
        if (!event.getEndDateTime().isAfter(now)) {
            throw new ApiProblemException(HttpStatus.CONFLICT, EVENT_ENDED, "That event has already ended.");
        }
        return event;
    }

    /** An accepted row of this very account, still on, that sells. */
    private static boolean sellsFor(EventScanner scanner, UUID accountUserUuid) {
        return scanner.isCanSell()
                && scanner.status() == EventScanner.Status.ACTIVE
                && accountUserUuid.equals(scanner.getAccountUserUuid());
    }

    /**
     * The scanner row, if it is this number's and not switched off. Anything
     * else (unknown, another number's, removed) is the same 404: an id is no
     * way to learn about other people's teams.
     */
    private EventScanner forThisNumber(UUID scannerId, String phone) {
        return scannerRepository.findById(scannerId)
                .filter(s -> s.getPhoneNumber().equals(phone) && s.getDisabledAt() == null)
                .orElseThrow(this::inviteNotFound);
    }

    private void requireNoClashOnReturn(EventScanner scanner) {
        scannerRepository.lockAllByPhoneNumber(scanner.getPhoneNumber());
        if (assignmentRepository.countClashesOnReturn(scanner.getPhoneNumber(), scanner.getScannerId(), now()) > 0) {
            log.info("Scanner return refused, schedule clash scannerId={}", scanner.getScannerId());
            throw scheduleClash();
        }
    }

    private static void requireARole(boolean canScan, boolean canSell) {
        if (!canScan && !canSell) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, NO_ROLE, NO_ROLE_MESSAGE);
        }
    }

    /**
     * The terms as stored: a cap of at least 1 or none, and a commission of 0
     * to 100 with at most 2 decimals (absent = 0), at scale 2. The request
     * DTO says the same through bean validation; this keeps a caller that
     * skips it from storing anything else, in the same 400 shape.
     */
    static BigDecimal requireValidTerms(Integer ticketCap, BigDecimal commissionPercent) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (ticketCap != null && ticketCap < 1) {
            errors.put("ticketCap", SalesEventTermsRequestDTO.CAP_MESSAGE);
        }
        BigDecimal commission = commissionPercent == null ? BigDecimal.ZERO : commissionPercent;
        if (commission.signum() < 0 || commission.compareTo(HUNDRED) > 0) {
            errors.put("commissionPercent", SalesEventTermsRequestDTO.COMMISSION_RANGE_MESSAGE);
        } else if (commission.stripTrailingZeros().scale() > 2) {
            errors.put("commissionPercent", SalesEventTermsRequestDTO.COMMISSION_DECIMALS_MESSAGE);
        }
        if (!errors.isEmpty()) {
            throw new FieldErrorsException(errors);
        }
        return commission.setScale(2, RoundingMode.UNNECESSARY);
    }

    /**
     * A light check only: user-service owns the address rules and answers its
     * own {@code invalid_email}. This keeps the column's bound and obvious
     * junk out of a remote call.
     */
    static String cleanEmail(String raw) {
        String email = raw == null ? "" : raw.trim();
        if (email.isEmpty()) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, INVALID_EMAIL, "Enter the agent's email address.",
                    Map.of("field", "email"));
        }
        if (email.length() > MAX_EMAIL_LENGTH || !EMAIL_SHAPE.matcher(email).matches()
                || email.chars().anyMatch(Character::isISOControl)) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, INVALID_EMAIL,
                    "That isn't a valid email address.", Map.of("field", "email"));
        }
        return email;
    }

    private String requirePhone(String sessionPhone) {
        if (sessionPhone == null || sessionPhone.isBlank()) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, PHONE_REQUIRED,
                    "Sign in with the InnBucks app to scan tickets.");
        }
        return MsisdnValidator.normalizeToE164(sessionPhone, deploymentCountry).orElse(sessionPhone.trim());
    }

    /** Each scanner's assigned events that are not deleted and have not ended, soonest first. */
    private Map<UUID, List<Event>> upcomingEventsByScanner(Collection<EventScanner> scanners, LocalDateTime now) {
        if (scanners.isEmpty()) {
            return Map.of();
        }
        Map<UUID, EventScanner> byId = scanners.stream()
                .collect(Collectors.toMap(EventScanner::getScannerId, Function.identity(), (a, b) -> a));
        List<EventScannerAssignment> rows = assignmentRepository.findByScannerIdIn(byId.keySet());
        Map<UUID, Event> events = eventRepository.findAllById(
                        rows.stream().map(EventScannerAssignment::getEventId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Event::getEventId, Function.identity()));
        Map<UUID, List<Event>> result = new HashMap<>();
        for (EventScannerAssignment row : rows) {
            Event event = events.get(row.getEventId());
            EventScanner scanner = byId.get(row.getScannerId());
            if (!upcomingFor(event, scanner, now)) {
                continue;
            }
            result.computeIfAbsent(row.getScannerId(), k -> new ArrayList<>()).add(event);
        }
        result.values().forEach(list -> list.sort(SOONEST_FIRST));
        return result;
    }

    /** As {@link #upcomingEventsByScanner}, for the events each row sells for, with its terms. */
    private Map<UUID, List<Sale>> upcomingSalesByScanner(Collection<EventScanner> scanners, LocalDateTime now) {
        if (scanners.isEmpty()) {
            return Map.of();
        }
        Map<UUID, EventScanner> byId = scanners.stream()
                .collect(Collectors.toMap(EventScanner::getScannerId, Function.identity(), (a, b) -> a));
        List<EventSalesAssignment> rows = salesAssignmentRepository.findByScannerIdIn(byId.keySet());
        if (rows.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Event> events = eventRepository.findAllById(
                        rows.stream().map(EventSalesAssignment::getEventId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Event::getEventId, Function.identity()));
        Map<UUID, List<Sale>> result = new HashMap<>();
        for (EventSalesAssignment row : rows) {
            Event event = events.get(row.getEventId());
            if (!upcomingFor(event, byId.get(row.getScannerId()), now)) {
                continue;
            }
            result.computeIfAbsent(row.getScannerId(), k -> new ArrayList<>()).add(new Sale(row, event));
        }
        result.values().forEach(list -> list.sort(Comparator.comparing(Sale::event, SOONEST_FIRST)));
        return result;
    }

    /** Not deleted, not ended, and the row's own organizer's. */
    private static boolean upcomingFor(Event event, EventScanner scanner, LocalDateTime now) {
        return event != null && scanner != null && !event.isDeleted() && event.getEndDateTime().isAfter(now)
                && scanner.getOrganizerUuid().equals(event.getTenantUserUuid());
    }

    private List<ScannerResponseDTO> toResponses(List<EventScanner> scanners) {
        if (scanners.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = scanners.stream().map(EventScanner::getScannerId).toList();
        Map<UUID, List<EventScannerAssignment>> rows = assignmentRepository.findByScannerIdIn(ids)
                .stream().collect(Collectors.groupingBy(EventScannerAssignment::getScannerId));
        Map<UUID, List<EventSalesAssignment>> salesRows = salesAssignmentRepository.findByScannerIdIn(ids)
                .stream().collect(Collectors.groupingBy(EventSalesAssignment::getScannerId));
        Set<UUID> eventIds = new HashSet<>();
        rows.values().forEach(list -> list.forEach(r -> eventIds.add(r.getEventId())));
        salesRows.values().forEach(list -> list.forEach(r -> eventIds.add(r.getEventId())));
        Map<UUID, Event> events = eventRepository.findAllById(eventIds)
                .stream().filter(e -> !e.isDeleted())
                .collect(Collectors.toMap(Event::getEventId, Function.identity()));
        List<ScannerResponseDTO> result = new ArrayList<>();
        for (EventScanner scanner : scanners) {
            List<Event> assigned = rows.getOrDefault(scanner.getScannerId(), List.of()).stream()
                    .map(r -> events.get(r.getEventId()))
                    .filter(Objects::nonNull)
                    .sorted(SOONEST_FIRST)
                    .toList();
            List<Sale> sales = salesRows.getOrDefault(scanner.getScannerId(), List.of()).stream()
                    .filter(r -> events.containsKey(r.getEventId()))
                    .map(r -> new Sale(r, events.get(r.getEventId())))
                    .sorted(Comparator.comparing(Sale::event, SOONEST_FIRST))
                    .toList();
            result.add(toResponse(scanner, assigned, sales));
        }
        return result;
    }

    private static ScannerResponseDTO toResponse(EventScanner scanner, List<Event> assigned, List<Sale> sales) {
        return new ScannerResponseDTO(scanner.getScannerId(), scanner.getOrganizerUuid(), scanner.getFirstName(),
                scanner.getLastName(), scanner.getPhoneNumber(), scanner.status().name(),
                scanner.isCanScan(), scanner.isCanSell(),
                assigned.stream().map(ScannerService::eventView).toList(),
                sales.stream().map(ScannerService::salesEventView).toList(),
                scanner.getPortalEmail(), scanner.getPortalInvitedAt(), scanner.getCreatedAt(),
                scanner.isAddedByStaff());
    }

    private static ScannerEventDTO eventView(Event event) {
        return new ScannerEventDTO(event.getEventId(), event.getTitle(), event.getVenue(),
                event.getStartDateTime(), event.getEndDateTime());
    }

    private static ScannerSalesEventDTO salesEventView(Sale sale) {
        Event event = sale.event();
        return new ScannerSalesEventDTO(event.getEventId(), event.getTitle(), event.getVenue(),
                event.getStartDateTime(), event.getEndDateTime(), sale.terms().getTicketCap(),
                sale.terms().getCommissionPercent());
    }

    private GateInviteDecisionDTO decision(EventScanner scanner) {
        Map<UUID, OrganizerDTO> names = organizerGateway.organizersByUserUuids(List.of(scanner.getOrganizerUuid()));
        return new GateInviteDecisionDTO(scanner.getScannerId(), scanner.status().name(),
                organizerName(names, scanner.getOrganizerUuid()));
    }

    private static String organizerName(Map<UUID, OrganizerDTO> names, UUID organizerUuid) {
        OrganizerDTO organizer = names.get(organizerUuid);
        return organizer == null ? null : organizer.getBusinessName();
    }

    private static String cleanName(String raw) {
        String cleaned = HtmlSanitizer.stripAll(raw);
        return cleaned == null ? "" : cleaned.trim();
    }

    private static List<EventScanner> concat(List<EventScanner> a, List<EventScanner> b) {
        List<EventScanner> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private ApiProblemException alreadyOnTeam(EventScanner existing, boolean staff) {
        return new ApiProblemException(HttpStatus.CONFLICT, ALREADY_ON_TEAM,
                staff ? "This number is already on this organizer's team." : "This number is already on your team.",
                Map.of("scannerId", existing.getScannerId()));
    }

    private static ApiProblemException salesAgentOrganizerOnly() {
        return new ApiProblemException(HttpStatus.FORBIDDEN, SALES_AGENT_ORGANIZER_ONLY,
                SALES_AGENT_ORGANIZER_ONLY_MESSAGE);
    }

    private ApiProblemException scannerNotFound() {
        return new ApiProblemException(HttpStatus.NOT_FOUND, SCANNER_NOT_FOUND, "Scanner not found.");
    }

    private ApiProblemException inviteNotFound() {
        return new ApiProblemException(HttpStatus.NOT_FOUND, INVITE_NOT_FOUND, "Invite not found.");
    }

    private static ApiProblemException notASalesAgent() {
        return new ApiProblemException(HttpStatus.CONFLICT, NOT_A_SALES_AGENT, NOT_A_SALES_AGENT_MESSAGE);
    }

    private static ApiProblemException portalInviteUnavailable() {
        return new ApiProblemException(HttpStatus.SERVICE_UNAVAILABLE, PORTAL_INVITE_UNAVAILABLE,
                PORTAL_INVITE_UNAVAILABLE_MESSAGE);
    }

    private static ApiProblemException scheduleClash() {
        return new ApiProblemException(HttpStatus.CONFLICT, SCHEDULE_CLASH, SCHEDULE_CLASH_MESSAGE);
    }
}
