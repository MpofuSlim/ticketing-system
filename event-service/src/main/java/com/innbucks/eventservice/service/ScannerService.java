package com.innbucks.eventservice.service;

import com.innbucks.eventservice.client.OrganizerGateway;
import com.innbucks.eventservice.dto.OrganizerDTO;
import com.innbucks.eventservice.dto.scanner.*;
import com.innbucks.eventservice.entity.Event;
import com.innbucks.eventservice.entity.EventScanner;
import com.innbucks.eventservice.entity.EventScannerAssignment;
import com.innbucks.eventservice.exception.ApiProblemException;
import com.innbucks.eventservice.repository.EventRepository;
import com.innbucks.eventservice.repository.EventScannerAssignmentRepository;
import com.innbucks.eventservice.repository.EventScannerRepository;
import com.innbucks.eventservice.util.HtmlSanitizer;
import com.innbucks.eventservice.util.MsisdnValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Super-app gate scanners (V14): an organizer names a phone number, the person
 * behind it accepts in the super app, and that account then scans the events
 * it is assigned to.
 *
 * <p><b>One rule spans organizers: a number is never on two events whose times
 * overlap</b> (owner decision, 2026-10-30; organizers may run overlapping
 * events, the person scanning may not be at both). It is checked whenever a
 * row starts counting toward the number's schedule (assign, enable, invite
 * again), under a lock on every row of the number, against the events' live
 * times. It is NOT re-checked when an organizer moves their own event: one
 * organizer's schedule change is never blocked by another's staffing. The
 * refusal names neither the other event nor its organizer.
 *
 * <p><b>Nothing here says whether a number has an InnBucks account.</b> Every
 * new scanner is INVITED; only the person, signed in on that number, can
 * accept. Accepting binds the row to the account that accepted, and only that
 * account scans for it, so a recycled number's new owner inherits nothing.
 *
 * <p>The super-app endpoints take the phone and the account from the caller's
 * session, never from a request.
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

    static final String SCHEDULE_CLASH_MESSAGE = "This number is already assigned to another event at that time.";

    private final EventScannerRepository scannerRepository;
    private final EventScannerAssignmentRepository assignmentRepository;
    private final EventRepository eventRepository;
    private final OrganizerGateway organizerGateway;

    /** The cell's country: how a number typed without a country code is read. */
    @Value("${innbucks.country:ZW}")
    private String deploymentCountry = "ZW";

    /**
     * Most scanners one organizer may name. A guard, not a quota: each one puts
     * an invite in that number's super app, so an unbounded list is a way to
     * fill strangers' apps.
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

    // ---------------------------------------------------------------- organizer

    @Transactional
    public ScannerResponseDTO add(UUID organizerUuid, String actor, CreateScannerRequestDTO request) {
        String phone = MsisdnValidator.normalizeToE164(request.phoneNumber(), deploymentCountry)
                .orElseThrow(() -> new ApiProblemException(HttpStatus.BAD_REQUEST, INVALID_PHONE,
                        "That isn't a valid mobile number.", Map.of("field", "phoneNumber")));
        Optional<EventScanner> existing = scannerRepository.findByOrganizerUuidAndPhoneNumber(organizerUuid, phone);
        if (existing.isPresent()) {
            throw alreadyOnTeam(existing.get());
        }
        if (scannerRepository.countByOrganizerUuid(organizerUuid) >= maxPerOrganizer) {
            throw new ApiProblemException(HttpStatus.CONFLICT, LIMIT_REACHED,
                    "Your team already has " + maxPerOrganizer + " scanners. Remove one before adding another.",
                    Map.of("limit", maxPerOrganizer));
        }
        EventScanner scanner = EventScanner.builder()
                .scannerId(UUID.randomUUID())
                .organizerUuid(organizerUuid)
                .phoneNumber(phone)
                .firstName(cleanName(request.firstName()))
                .lastName(cleanName(request.lastName()))
                .createdBy(actor)
                .build();
        try {
            scannerRepository.saveAndFlush(scanner);
        } catch (DataIntegrityViolationException race) {
            // Two adds of one number at once: the unique key let one in.
            throw scannerRepository.findByOrganizerUuidAndPhoneNumber(organizerUuid, phone)
                    .map(this::alreadyOnTeam)
                    .orElseThrow(() -> race);
        }
        log.info("Scanner added scannerId={} organizerUuid={} by={}", scanner.getScannerId(), organizerUuid, actor);
        return toResponse(scanner, List.of());
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

    @Transactional
    public ScannerResponseDTO assign(UUID organizerUuid, String actor, UUID scannerId, UUID eventId) {
        EventScanner scanner = owned(organizerUuid, scannerId);
        Event event = eventRepository.findById(eventId)
                .filter(e -> !e.isDeleted() && organizerUuid.equals(e.getTenantUserUuid()))
                .orElseThrow(() -> new ApiProblemException(HttpStatus.NOT_FOUND, EVENT_NOT_FOUND,
                        "Event not found."));
        LocalDateTime now = now();
        if (!event.getEndDateTime().isAfter(now)) {
            throw new ApiProblemException(HttpStatus.CONFLICT, EVENT_ENDED, "That event has already ended.");
        }
        // Every row of the number, first: the rule spans organizers, so a
        // concurrent assignment of the same number elsewhere waits here. The
        // already-assigned check comes after it, so two identical requests at
        // once answer the second from the first's committed row.
        scannerRepository.lockAllByPhoneNumber(scanner.getPhoneNumber());
        if (assignmentRepository.existsByScannerIdAndEventId(scannerId, eventId)) {
            return get(organizerUuid, scannerId);
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
        return get(organizerUuid, scannerId);
    }

    @Transactional
    public ScannerResponseDTO unassign(UUID organizerUuid, String actor, UUID scannerId, UUID eventId) {
        owned(organizerUuid, scannerId);
        if (assignmentRepository.deleteByScannerIdAndEventId(scannerId, eventId) > 0) {
            log.info("Scanner unassigned scannerId={} eventId={} by={}", scannerId, eventId, actor);
        }
        return get(organizerUuid, scannerId);
    }

    /** Switched off: the next scan is refused; the record and its assignments stay. */
    @Transactional
    public ScannerResponseDTO disable(UUID organizerUuid, String actor, UUID scannerId) {
        EventScanner scanner = owned(organizerUuid, scannerId);
        if (scanner.getDisabledAt() == null) {
            scanner.setDisabledAt(now());
            scannerRepository.save(scanner);
            log.info("Scanner disabled scannerId={} by={}", scannerId, actor);
        }
        return get(organizerUuid, scannerId);
    }

    @Transactional
    public ScannerResponseDTO enable(UUID organizerUuid, String actor, UUID scannerId) {
        EventScanner scanner = owned(organizerUuid, scannerId);
        if (scanner.getDisabledAt() != null) {
            // Back on, its events count again: they must not overlap what the
            // number took on elsewhere while this row was off.
            requireNoClashOnReturn(scanner);
            scanner.setDisabledAt(null);
            scannerRepository.save(scanner);
            log.info("Scanner enabled scannerId={} by={}", scannerId, actor);
        }
        return get(organizerUuid, scannerId);
    }

    /** A DECLINED scanner (declined, or left) back to INVITED. */
    @Transactional
    public ScannerResponseDTO reinvite(UUID organizerUuid, String actor, UUID scannerId) {
        EventScanner scanner = owned(organizerUuid, scannerId);
        if (scanner.getDeclinedAt() == null) {
            throw new ApiProblemException(HttpStatus.CONFLICT, NOT_DECLINED,
                    "Only a scanner who declined can be invited again.");
        }
        if (scanner.getDisabledAt() == null) {
            requireNoClashOnReturn(scanner);
        }
        scanner.setDeclinedAt(null);
        scannerRepository.save(scanner);
        log.info("Scanner invited again scannerId={} by={}", scannerId, actor);
        return get(organizerUuid, scannerId);
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

        List<EventScanner> active = accountUserUuid == null
                ? List.of() : scannerRepository.findActiveForAccount(accountUserUuid);
        List<EventScanner> pending = scannerRepository.findPendingForPhone(phone);
        if (active.isEmpty() && pending.isEmpty()) {
            return new GateResponseDTO(List.of(), List.of());
        }

        Map<UUID, List<Event>> upcoming = upcomingEventsByScanner(concat(active, pending), now);
        Set<UUID> organizers = new LinkedHashSet<>();
        active.forEach(s -> organizers.add(s.getOrganizerUuid()));
        pending.forEach(s -> organizers.add(s.getOrganizerUuid()));
        Map<UUID, OrganizerDTO> names = organizerGateway.organizersByUserUuids(organizers);

        List<GateEventDTO> events = new ArrayList<>();
        for (EventScanner scanner : active) {
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
                        upcoming.getOrDefault(s.getScannerId(), List.of()).stream()
                                .map(ScannerService::eventView).toList()))
                .toList();
        return new GateResponseDTO(events, invites);
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
     * switched-on scanner assigned to this event, on that event's organizer's
     * team? Read live on every scan, so a removal bites at the next scan.
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
            if (scanner.getOrganizerUuid().equals(event.get().getTenantUserUuid())
                    && assignmentRepository.existsByScannerIdAndEventId(scanner.getScannerId(), eventId)) {
                return new ScannerAccessDTO(true, scanner.getScannerId(), scanner.getOrganizerUuid(),
                        scanner.displayName());
            }
        }
        return ScannerAccessDTO.denied();
    }

    // ------------------------------------------------------------------- helpers

    private EventScanner owned(UUID organizerUuid, UUID scannerId) {
        return scannerRepository.findByScannerIdAndOrganizerUuid(scannerId, organizerUuid)
                .orElseThrow(this::scannerNotFound);
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
            if (event == null || event.isDeleted() || !event.getEndDateTime().isAfter(now)
                    || !scanner.getOrganizerUuid().equals(event.getTenantUserUuid())) {
                continue;
            }
            result.computeIfAbsent(row.getScannerId(), k -> new ArrayList<>()).add(event);
        }
        result.values().forEach(list -> list.sort(
                Comparator.comparing(Event::getStartDateTime).thenComparing(Event::getEventId)));
        return result;
    }

    private List<ScannerResponseDTO> toResponses(List<EventScanner> scanners) {
        if (scanners.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<EventScannerAssignment>> rows = assignmentRepository
                .findByScannerIdIn(scanners.stream().map(EventScanner::getScannerId).toList())
                .stream().collect(Collectors.groupingBy(EventScannerAssignment::getScannerId));
        Map<UUID, Event> events = eventRepository.findAllById(rows.values().stream()
                        .flatMap(List::stream).map(EventScannerAssignment::getEventId).collect(Collectors.toSet()))
                .stream().filter(e -> !e.isDeleted())
                .collect(Collectors.toMap(Event::getEventId, Function.identity()));
        List<ScannerResponseDTO> result = new ArrayList<>();
        for (EventScanner scanner : scanners) {
            List<Event> assigned = rows.getOrDefault(scanner.getScannerId(), List.of()).stream()
                    .map(r -> events.get(r.getEventId()))
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparing(Event::getStartDateTime).thenComparing(Event::getEventId))
                    .toList();
            result.add(toResponse(scanner, assigned));
        }
        return result;
    }

    private static ScannerResponseDTO toResponse(EventScanner scanner, List<Event> assigned) {
        return new ScannerResponseDTO(scanner.getScannerId(), scanner.getOrganizerUuid(), scanner.getFirstName(),
                scanner.getLastName(), scanner.getPhoneNumber(), scanner.status().name(),
                assigned.stream().map(ScannerService::eventView).toList(), scanner.getCreatedAt());
    }

    private static ScannerEventDTO eventView(Event event) {
        return new ScannerEventDTO(event.getEventId(), event.getTitle(), event.getVenue(),
                event.getStartDateTime(), event.getEndDateTime());
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

    private ApiProblemException alreadyOnTeam(EventScanner existing) {
        return new ApiProblemException(HttpStatus.CONFLICT, ALREADY_ON_TEAM,
                "This number is already on your team.", Map.of("scannerId", existing.getScannerId()));
    }

    private ApiProblemException scannerNotFound() {
        return new ApiProblemException(HttpStatus.NOT_FOUND, SCANNER_NOT_FOUND, "Scanner not found.");
    }

    private ApiProblemException inviteNotFound() {
        return new ApiProblemException(HttpStatus.NOT_FOUND, INVITE_NOT_FOUND, "Invite not found.");
    }

    private static ApiProblemException scheduleClash() {
        return new ApiProblemException(HttpStatus.CONFLICT, SCHEDULE_CLASH, SCHEDULE_CLASH_MESSAGE);
    }
}
