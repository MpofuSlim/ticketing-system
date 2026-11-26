package com.innbucks.eventservice.service;

import com.innbucks.eventservice.cache.PublicEventCatalog;
import com.innbucks.eventservice.client.SeatLayoutGateway;
import com.innbucks.eventservice.config.MarketTimeZone;
import com.innbucks.eventservice.entity.Event;
import com.innbucks.eventservice.entity.EventBookingQuestion;
import com.innbucks.eventservice.entity.EventSalesAssignment;
import com.innbucks.eventservice.entity.EventSalesWindowChange;
import com.innbucks.eventservice.entity.EventScanner;
import com.innbucks.eventservice.entity.EventScannerAssignment;
import com.innbucks.eventservice.entity.EventSeries;
import com.innbucks.eventservice.entity.Location;
import com.innbucks.eventservice.entity.SeriesState;
import com.innbucks.eventservice.repository.EventBookingQuestionRepository;
import com.innbucks.eventservice.repository.EventRepository;
import com.innbucks.eventservice.repository.EventSalesAssignmentRepository;
import com.innbucks.eventservice.repository.EventSalesWindowChangeRepository;
import com.innbucks.eventservice.repository.EventScannerAssignmentRepository;
import com.innbucks.eventservice.repository.EventScannerRepository;
import com.innbucks.eventservice.repository.EventSeriesRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Makes each recurring series' dates (V24, TIC-70): every start its schedule
 * has inside the booking window, as a copy of the series' template.
 *
 * <p><b>A date is made in three steps, and never with a remote call inside a
 * transaction.</b>
 * <ol>
 *   <li>One transaction inserts the date, hidden ({@link SeriesState#GENERATING},
 *       inactive), with the template's fields, banner, terms and gate team.</li>
 *   <li>With no transaction open, seat-service copies the template's seat
 *       layout onto it ({@link SeatLayoutGateway}). Idempotent there, so a
 *       lost answer is simply asked again next run.</li>
 *   <li>One transaction locks the date, copies the booking questions (they
 *       name seat categories, so they wait for the layout's new ids) and
 *       marks it {@link SeriesState#READY}: live when the series is.</li>
 * </ol>
 * A date that fails at step 2 or 3 stays GENERATING (hidden) and is finished
 * by a later run.
 *
 * <p><b>Why no ShedLock:</b> every step is safe on several replicas at once.
 * Two runs making the same start meet at {@code uq_events_series_start} and
 * one insert fails; two layout copies onto one date queue on seat-service's
 * advisory lock and the second copies nothing; the last step locks the date
 * row and does nothing once it is READY; the template's times are written
 * under its {@code @Version}. The only cost of a second replica is the
 * repeated reads.
 */
@Component
@Slf4j
public class SeriesDateGenerator {

    /** How many of a series' unfinished dates one run tries to finish. */
    static final int FINISH_BATCH = 100;

    private final SeriesPolicy policy;
    private final EventSeriesRepository seriesRepository;
    private final EventRepository eventRepository;
    private final EventBookingQuestionRepository questionRepository;
    private final EventScannerRepository scannerRepository;
    private final EventScannerAssignmentRepository scanAssignments;
    private final EventSalesAssignmentRepository salesAssignments;
    private final EventSalesWindowChangeRepository salesWindowChanges;
    private final EventTermsAndQuestions termsAndQuestions;
    private final SeatLayoutGateway seatLayoutGateway;
    private final MarketTimeZone marketTimeZone;
    private final PublicEventCatalog publicEventCatalog;
    private final TransactionTemplate writeTx;
    private final TransactionTemplate readTx;
    private Clock clock = Clock.systemUTC();

    public SeriesDateGenerator(SeriesPolicy policy,
                               EventSeriesRepository seriesRepository,
                               EventRepository eventRepository,
                               EventBookingQuestionRepository questionRepository,
                               EventScannerRepository scannerRepository,
                               EventScannerAssignmentRepository scanAssignments,
                               EventSalesAssignmentRepository salesAssignments,
                               EventSalesWindowChangeRepository salesWindowChanges,
                               EventTermsAndQuestions termsAndQuestions,
                               SeatLayoutGateway seatLayoutGateway,
                               MarketTimeZone marketTimeZone,
                               PublicEventCatalog publicEventCatalog,
                               PlatformTransactionManager transactionManager) {
        this.policy = policy;
        this.seriesRepository = seriesRepository;
        this.eventRepository = eventRepository;
        this.questionRepository = questionRepository;
        this.scannerRepository = scannerRepository;
        this.scanAssignments = scanAssignments;
        this.salesAssignments = salesAssignments;
        this.salesWindowChanges = salesWindowChanges;
        this.termsAndQuestions = termsAndQuestions;
        this.seatLayoutGateway = seatLayoutGateway;
        this.marketTimeZone = marketTimeZone;
        this.publicEventCatalog = publicEventCatalog;
        this.writeTx = transactionManager == null ? null : new TransactionTemplate(transactionManager);
        if (transactionManager == null) {
            this.readTx = null;
        } else {
            TransactionTemplate read = new TransactionTemplate(transactionManager);
            read.setReadOnly(true);
            read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
            this.readTx = read;
        }
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    /** What one run did. */
    public record Summary(int created, int finished, int waiting, int failed) {
        static final Summary NONE = new Summary(0, 0, 0, 0);

        Summary plus(Summary other) {
            return new Summary(created + other.created, finished + other.finished, waiting + other.waiting,
                    failed + other.failed);
        }

        boolean idle() {
            return created == 0 && finished == 0 && waiting == 0 && failed == 0;
        }
    }

    @Scheduled(cron = "${events.series.generator-cron:0 */5 * * * *}", zone = "UTC")
    public void scheduledRun() {
        if (!policy.enabled()) {
            return;
        }
        try {
            run();
        } catch (RuntimeException e) {
            log.error("Series generator run failed cause={}", e.toString(), e);
        }
    }

    /** One pass over every series that is not ended. */
    public Summary run() {
        Instant deadline = clock.instant().plus(policy.runBudget());
        int budget = policy.maxDatesPerRun();
        List<UUID> seriesIds = seriesRepository.findIdsByStatusIn(List.of(
                EventSeries.Status.ACTIVE, EventSeries.Status.DRAFT, EventSeries.Status.PAUSED));
        Summary total = Summary.NONE;
        int done = 0;
        for (UUID seriesId : seriesIds) {
            if (clock.instant().isAfter(deadline) || total.created() >= budget) {
                log.info("Series generator stopped for this run created={} seriesLeft={} (the next run continues)",
                        total.created(), seriesIds.size() - done);
                break;
            }
            try {
                total = total.plus(runOne(seriesId, budget - total.created(), deadline));
            } catch (RuntimeException e) {
                log.warn("Series generator failed on a series seriesId={} cause={}", seriesId, e.toString());
                total = total.plus(new Summary(0, 0, 0, 1));
            }
            done++;
        }
        if (!total.idle()) {
            log.info("Series generator run created={} finished={} waiting={} failed={} series={}",
                    total.created(), total.finished(), total.waiting(), total.failed(), seriesIds.size());
        }
        return total;
    }

    /** What one series needs this run. */
    record Plan(UUID seriesId, UUID templateId, LocalDateTime nextStartUtc, int durationMinutes,
                List<LocalDateTime> missingStartsUtc) {
    }

    Summary runOne(UUID seriesId, int maxNew, Instant deadline) {
        Plan plan = read(() -> plan(seriesId));
        if (plan == null) {
            return Summary.NONE;
        }
        refreshTemplate(plan);

        int created = 0;
        int failed = 0;
        for (LocalDateTime startUtc : plan.missingStartsUtc()) {
            if (created >= maxNew || clock.instant().isAfter(deadline)) {
                break;
            }
            try {
                UUID dateId = write(() -> createDate(seriesId, startUtc));
                if (dateId != null) {
                    created++;
                }
            } catch (DataIntegrityViolationException e) {
                // Another run made this start first (uq_events_series_start).
                log.info("Series date already made by another run seriesId={} startUtc={}", seriesId, startUtc);
            } catch (RuntimeException e) {
                failed++;
                log.warn("Series date could not be made seriesId={} startUtc={} cause={}",
                        seriesId, startUtc, e.toString());
            }
        }

        int finished = 0;
        int waiting = 0;
        List<UUID> unfinished = read(() -> eventRepository.findGeneratingDateIds(seriesId,
                PageRequest.of(0, FINISH_BATCH)));
        for (UUID dateId : unfinished) {
            if (clock.instant().isAfter(deadline)) {
                waiting += 1;
                continue;
            }
            try {
                Finish result = finishDate(plan.templateId(), dateId);
                if (result == Finish.READY) {
                    finished++;
                } else if (result == Finish.WAITING) {
                    waiting++;
                }
            } catch (RuntimeException e) {
                failed++;
                log.warn("Series date could not be finished seriesId={} dateId={} cause={}",
                        seriesId, dateId, e.toString());
            }
        }
        return new Summary(created, finished, waiting, failed);
    }

    Plan plan(UUID seriesId) {
        EventSeries series = seriesRepository.findById(seriesId).orElse(null);
        if (series == null || series.getStatus() == EventSeries.Status.ENDED) {
            return null;
        }
        Event template = eventRepository.findById(series.getTemplateEventId()).orElse(null);
        if (template == null || !SeriesState.isTemplate(template)) {
            return null;
        }
        SeriesSchedule schedule = SeriesSchedule.of(series);
        LocalDateTime nowUtc = nowUtc();
        LocalDateTime nowLocal = marketTimeZone.toMarketLocal(nowUtc);
        LocalDateTime nextStartUtc = schedule.firstStartFrom(nowLocal).map(marketTimeZone::toUtc).orElse(null);

        List<LocalDateTime> missing = List.of();
        if (series.getStatus() == EventSeries.Status.ACTIVE && !template.isDeleted()) {
            // A date whose sales would already be closed is not worth making.
            int closeMinutes = series.getSalesCloseMinutesBefore() == null ? 0 : series.getSalesCloseMinutesBefore();
            LocalDateTime from = nowLocal.plusMinutes(closeMinutes);
            LocalDateTime to = nowLocal.plusDays(series.getBookingWindowDays());
            Set<LocalDateTime> made = new HashSet<>(eventRepository.findSeriesStartsFrom(seriesId, nowUtc));
            long room = SeriesSchedule.MAX_DATES_IN_WINDOW - eventRepository.countSeriesDatesFrom(seriesId, nowUtc);
            missing = schedule.startsBetween(from, to).stream()
                    .map(marketTimeZone::toUtc)
                    .filter(start -> !made.contains(start))
                    .limit(Math.max(0, room))
                    .toList();
        }
        return new Plan(seriesId, template.getEventId(), nextStartUtc, series.getDurationMinutes(), missing);
    }

    /**
     * Keeps the template's times at the series' next start, so its set-up
     * (assigning the gate team, for one) is never refused as "already ended".
     */
    void refreshTemplate(Plan plan) {
        if (plan.nextStartUtc() == null) {
            return;
        }
        try {
            write(() -> {
                Event template = eventRepository.findById(plan.templateId()).orElse(null);
                if (template == null || template.isDeleted() || !SeriesState.isTemplate(template)
                        || plan.nextStartUtc().equals(template.getStartDateTime())) {
                    return null;
                }
                template.setStartDateTime(plan.nextStartUtc());
                template.setEndDateTime(plan.nextStartUtc().plusMinutes(plan.durationMinutes()));
                eventRepository.save(template);
                return null;
            });
        } catch (ObjectOptimisticLockingFailureException e) {
            log.info("Series template edited during the refresh; the next run moves it templateEventId={}",
                    plan.templateId());
        }
    }

    /** Step 1: the date, hidden, with the template's fields, banner, terms and team. Null when not made. */
    UUID createDate(UUID seriesId, LocalDateTime startUtc) {
        EventSeries series = seriesRepository.findById(seriesId).orElse(null);
        if (series == null || series.getStatus() != EventSeries.Status.ACTIVE) {
            return null;
        }
        Event template = eventRepository.findById(series.getTemplateEventId()).orElse(null);
        if (template == null || template.isDeleted() || !SeriesState.isTemplate(template)) {
            return null;
        }
        if (eventRepository.existsBySeriesIdAndStartDateTimeAndSeriesStateIn(seriesId, startUtc,
                List.of(SeriesState.GENERATING, SeriesState.READY))) {
            return null;
        }
        LocalDateTime now = nowUtc();
        LocalDateTime endUtc = startUtc.plusMinutes(series.getDurationMinutes());
        LocalDateTime salesStart = series.getSalesOpenMinutesBefore() == null
                ? null : startUtc.minusMinutes(series.getSalesOpenMinutesBefore());
        LocalDateTime salesEnd = series.getSalesCloseMinutesBefore() == null
                ? null : startUtc.minusMinutes(series.getSalesCloseMinutesBefore());
        String terms = termsAndQuestions == null ? null : termsAndQuestions.currentText(template);
        Location location = template.getLocation() == null ? null
                : new Location(template.getLocation().getLatitude(), template.getLocation().getLongitude());

        Event date = Event.builder()
                .tenantUserUuid(template.getTenantUserUuid())
                .title(template.getTitle())
                .settlementCode(template.getSettlementCode())
                .description(template.getDescription())
                .venue(template.getVenue())
                .country(template.getCountry())
                .category(template.getCategory())
                .location(location)
                .startDateTime(startUtc)
                .endDateTime(endUtc)
                .salesStartAt(salesStart)
                .salesEndAt(salesEnd)
                .termsVersion(terms == null ? null : 1)
                .totalCapacity(template.getTotalCapacity())
                .availableTickets(template.getTotalCapacity())
                .bannerContentType(template.getBannerContentType())
                .deleted(false)
                .active(false)
                .rejected(false)
                // The series was reviewed through its template.
                .submittedAt(template.getSubmittedAt())
                .approvedAt(now)
                .approvedBy(template.getApprovedBy())
                .seriesId(seriesId)
                .seriesState(SeriesState.GENERATING)
                .build();
        eventRepository.saveAndFlush(date);
        UUID dateId = date.getEventId();

        if (template.getBannerContentType() != null) {
            eventRepository.findBannerImage(template.getEventId())
                    .filter(bytes -> bytes.length > 0)
                    .ifPresent(bytes -> eventRepository.writeBannerImage(dateId, bytes));
        }
        if (terms != null) {
            termsAndQuestions.recordFirstVersion(dateId, terms);
        }
        if (salesStart != null || salesEnd != null) {
            salesWindowChanges.save(EventSalesWindowChange.builder()
                    .eventId(dateId)
                    .changeType(EventSalesWindowChange.ChangeType.CREATED)
                    .newSalesStartAt(salesStart)
                    .newSalesEndAt(salesEnd)
                    .build());
        }
        int skipped = copyTeam(template, date, now);
        log.info("Series date made seriesId={} dateId={} startUtc={} scannersSkippedForClash={}",
                seriesId, dateId, startUtc, skipped);
        return dateId;
    }

    /**
     * The template's gate team and sales agents, onto the date. A scanner
     * whose number would then be at two overlapping events is left off this
     * date (the overlap rule, checked as an organizer's assignment is). Nobody
     * is messaged: the template's assignment already invited them. Returns how
     * many scanners were left off.
     */
    private int copyTeam(Event template, Event date, LocalDateTime now) {
        List<EventScannerAssignment> scans = scanAssignments.findByEventId(template.getEventId());
        int skipped = 0;
        if (!scans.isEmpty()) {
            Map<UUID, EventScanner> scanners = scannerRepository.findAllById(
                            scans.stream().map(EventScannerAssignment::getScannerId).collect(Collectors.toSet()))
                    .stream()
                    .filter(s -> Objects.equals(s.getOrganizerUuid(), date.getTenantUserUuid()))
                    .collect(Collectors.toMap(EventScanner::getScannerId, Function.identity()));
            // Every row of each number, in one order, before the overlap check:
            // the rule spans organizers, so a concurrent assignment waits here.
            Set<String> phones = new TreeSet<>();
            scanners.values().forEach(s -> phones.add(s.getPhoneNumber()));
            phones.forEach(scannerRepository::lockAllByPhoneNumber);
            for (EventScannerAssignment scan : scans) {
                EventScanner scanner = scanners.get(scan.getScannerId());
                if (scanner == null) {
                    continue;
                }
                long clashes = scanAssignments.countClashes(scanner.getPhoneNumber(), scanner.getScannerId(),
                        date.getEventId(), date.getStartDateTime(), date.getEndDateTime(), now);
                if (clashes > 0) {
                    skipped++;
                    continue;
                }
                scanAssignments.save(EventScannerAssignment.builder()
                        .assignmentId(UUID.randomUUID())
                        .scannerId(scanner.getScannerId())
                        .eventId(date.getEventId())
                        .assignedBy(scan.getAssignedBy())
                        .build());
            }
        }
        for (EventSalesAssignment sale : salesAssignments.findByEventId(template.getEventId())) {
            salesAssignments.save(EventSalesAssignment.builder()
                    .assignmentId(UUID.randomUUID())
                    .scannerId(sale.getScannerId())
                    .eventId(date.getEventId())
                    .ticketCap(sale.getTicketCap())
                    .commissionPercent(sale.getCommissionPercent())
                    .assignedBy(sale.getAssignedBy())
                    .build());
        }
        return skipped;
    }

    /** What {@link #finishDate} came to. */
    enum Finish {
        /** This call made the date READY. */
        READY,
        /** Its seat layout could not be copied yet; the next run asks again. */
        WAITING,
        /** Nothing to do: gone, or another run finished it. */
        NOTHING
    }

    /** Steps 2 and 3 for a date still being made. */
    Finish finishDate(UUID templateId, UUID dateId) {
        Event date = read(() -> eventRepository.findById(dateId).orElse(null));
        if (date == null || !SeriesState.GENERATING.equals(date.getSeriesState())) {
            return Finish.NOTHING;
        }
        SeatLayoutGateway.LayoutCopy copy = seatLayoutGateway.copyLayout(templateId, dateId,
                date.getTotalCapacity() == null ? 0 : date.getTotalCapacity());
        if (copy.outcome() != SeatLayoutGateway.Outcome.DONE) {
            log.warn("Series date waits for its seat layout dateId={} outcome={}", dateId, copy.outcome());
            return Finish.WAITING;
        }
        Boolean live = write(() -> {
            Event locked = eventRepository.lockById(dateId).orElse(null);
            if (locked == null || !SeriesState.GENERATING.equals(locked.getSeriesState())) {
                return null;
            }
            int questions = copyQuestions(templateId, dateId, copy.categoryIds());
            EventSeries series = locked.getSeriesId() == null ? null
                    : seriesRepository.findById(locked.getSeriesId()).orElse(null);
            boolean goesLive = series != null && series.getStatus() == EventSeries.Status.ACTIVE
                    && !locked.isDeleted() && !locked.isRejected();
            locked.setSeriesState(SeriesState.READY);
            locked.setActive(goesLive);
            eventRepository.save(locked);
            log.info("Series date ready dateId={} live={} categories={} questions={}",
                    dateId, goesLive, copy.categoryIds().size(), questions);
            return goesLive;
        });
        if (live == null) {
            return Finish.NOTHING;
        }
        if (live) {
            publicEventCatalog.invalidateAll();
        }
        return Finish.READY;
    }

    /**
     * The template's booking questions, onto the date, with their seat
     * categories mapped to the date's own. A question limited to categories
     * none of which reached the date is left off: with an empty list it would
     * be asked on every ticket instead.
     */
    private int copyQuestions(UUID templateId, UUID dateId, Map<UUID, UUID> categoryIds) {
        LocalDateTime now = nowUtc();
        List<EventBookingQuestion> copies = new ArrayList<>();
        for (EventBookingQuestion question : questionRepository.findByEventIdOrderByPositionAsc(templateId)) {
            List<String> categories = new ArrayList<>();
            if (question.getCategoryIds() != null && !question.getCategoryIds().isEmpty()) {
                for (String id : question.getCategoryIds()) {
                    UUID mapped = mapped(categoryIds, id);
                    if (mapped != null) {
                        categories.add(mapped.toString());
                    }
                }
                if (categories.isEmpty()) {
                    log.warn("Series date question left off, none of its categories reached the date "
                            + "dateId={} questionId={}", dateId, question.getId());
                    continue;
                }
            }
            copies.add(EventBookingQuestion.builder()
                    .id(UUID.randomUUID())
                    .eventId(dateId)
                    .position(copies.size())
                    .label(question.getLabel())
                    .type(question.getType())
                    .required(question.isRequired())
                    .helpText(question.getHelpText())
                    .options(question.getOptions() == null ? new ArrayList<>() : new ArrayList<>(question.getOptions()))
                    .maxLength(question.getMaxLength())
                    .categoryIds(categories)
                    .createdAt(now)
                    .updatedAt(now)
                    .build());
        }
        if (!copies.isEmpty()) {
            questionRepository.saveAll(copies);
        }
        return copies.size();
    }

    private static UUID mapped(Map<UUID, UUID> categoryIds, String sourceId) {
        try {
            return categoryIds.get(UUID.fromString(sourceId));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private <T> T read(java.util.function.Supplier<T> work) {
        return readTx == null ? work.get() : readTx.execute(status -> work.get());
    }

    private <T> T write(java.util.function.Supplier<T> work) {
        return writeTx == null ? work.get() : writeTx.execute(status -> work.get());
    }
}
