package com.innbucks.eventservice.service;

import com.innbucks.eventservice.entity.EventSeries;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * A series' schedule (V24, TIC-70) and the starts it makes. Everything here is
 * the market's local wall clock, exactly as the organizer typed it; the caller
 * converts a start to UTC. No clock and no zone: the caller passes "now".
 *
 * <p>The regular starts are every listed time on every listed day of the week,
 * from {@code startDate} to {@code endDate} (inclusive; none = no end), except
 * on a skipped date. An extra start is added whatever the days, the dates and
 * the skips say: it is the organizer's explicit one-off.
 */
public final class SeriesSchedule {

    public static final int MAX_START_TIMES = 10;
    public static final int MAX_SKIP_DATES = 366;
    public static final int MAX_EXTRA_STARTS = 100;
    public static final int MAX_DURATION_MINUTES = 24 * 60;
    public static final int MAX_BOOKING_WINDOW_DAYS = 180;
    public static final int DEFAULT_BOOKING_WINDOW_DAYS = 60;
    /** The most minutes before a start that sales may open or close: the longest booking window. */
    public static final int MAX_SALES_MINUTES = MAX_BOOKING_WINDOW_DAYS * 24 * 60;
    /** The most dates a series may hold in its booking window at once. */
    public static final int MAX_DATES_IN_WINDOW = 400;
    /** How far ahead the first start is looked for, in days. */
    static final int FIRST_START_SEARCH_DAYS = 2 * 366;

    // STRICT: a 30 February or a 24:00 is refused, never moved to a nearby valid value.
    static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm")
            .withResolverStyle(ResolverStyle.STRICT);
    static final DateTimeFormatter EXTRA = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm")
            .withResolverStyle(ResolverStyle.STRICT);

    private final Set<DayOfWeek> days;
    private final List<LocalTime> startTimes;
    private final int durationMinutes;
    private final LocalDate startDate;
    private final LocalDate endDate;
    private final Set<LocalDate> skipDates;
    private final List<LocalDateTime> extraStarts;

    SeriesSchedule(Set<DayOfWeek> days, Collection<LocalTime> startTimes, int durationMinutes,
                   LocalDate startDate, LocalDate endDate, Collection<LocalDate> skipDates,
                   Collection<LocalDateTime> extraStarts) {
        this.days = days.isEmpty() ? EnumSet.noneOf(DayOfWeek.class) : EnumSet.copyOf(days);
        this.startTimes = List.copyOf(new TreeSet<>(startTimes));
        this.durationMinutes = durationMinutes;
        this.startDate = startDate;
        this.endDate = endDate;
        this.skipDates = Set.copyOf(skipDates);
        this.extraStarts = List.copyOf(new TreeSet<>(extraStarts));
    }

    /** The stored schedule. Stored values were checked when they were saved, so a bad one is a bug. */
    public static SeriesSchedule of(EventSeries series) {
        Map<String, String> errors = new java.util.LinkedHashMap<>();
        SeriesSchedule schedule = parse(series.getDaysOfWeek(), series.getStartTimes(), series.getDurationMinutes(),
                series.getStartDate(), series.getEndDate(), series.getSkipDates(), series.getExtraStarts(), errors);
        if (!errors.isEmpty()) {
            throw new IllegalStateException("Stored schedule of series " + series.getSeriesId()
                    + " is not valid: " + errors);
        }
        return schedule;
    }

    /**
     * Reads a schedule as sent. Each problem is added to {@code errors} under
     * the request's field name ({@code startTimes[1]}); null when there is any.
     * Repeated days, times and dates count once.
     */
    public static SeriesSchedule parse(List<String> daysOfWeek, List<String> startTimes, int durationMinutes,
                                       LocalDate startDate, LocalDate endDate, List<String> skipDates,
                                       List<String> extraStarts, Map<String, String> errors) {
        int before = errors.size();
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        if (daysOfWeek == null || daysOfWeek.isEmpty()) {
            errors.put("daysOfWeek", "Pick at least one day of the week.");
        } else {
            for (int i = 0; i < daysOfWeek.size(); i++) {
                String raw = daysOfWeek.get(i);
                try {
                    days.add(DayOfWeek.valueOf(raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException e) {
                    errors.put("daysOfWeek[" + i + "]", "Use MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, "
                            + "SATURDAY or SUNDAY.");
                }
            }
        }

        List<LocalTime> times = new ArrayList<>();
        if (startTimes == null || startTimes.isEmpty()) {
            errors.put("startTimes", "Add at least one start time, like 19:00.");
        } else if (startTimes.size() > MAX_START_TIMES) {
            errors.put("startTimes", "At most " + MAX_START_TIMES + " start times a day.");
        } else {
            for (int i = 0; i < startTimes.size(); i++) {
                LocalTime time = parseTime(startTimes.get(i));
                if (time == null) {
                    errors.put("startTimes[" + i + "]", "Use the 24-hour time, like 19:00.");
                } else {
                    times.add(time);
                }
            }
        }

        if (durationMinutes < 1 || durationMinutes > MAX_DURATION_MINUTES) {
            errors.put("durationMinutes", "Must be between 1 and " + MAX_DURATION_MINUTES + " minutes.");
        }
        if (startDate == null) {
            errors.put("startDate", "Say the first day of the series, like 2026-12-01.");
        } else if (endDate != null && endDate.isBefore(startDate)) {
            errors.put("endDate", "The last day can't be before the first day.");
        }

        List<LocalDate> skips = new ArrayList<>();
        if (skipDates != null) {
            if (skipDates.size() > MAX_SKIP_DATES) {
                errors.put("skipDates", "At most " + MAX_SKIP_DATES + " skipped dates.");
            } else {
                for (int i = 0; i < skipDates.size(); i++) {
                    LocalDate date = parseDate(skipDates.get(i));
                    if (date == null) {
                        errors.put("skipDates[" + i + "]", "Use a date like 2026-12-25.");
                    } else {
                        skips.add(date);
                    }
                }
            }
        }

        List<LocalDateTime> extras = new ArrayList<>();
        if (extraStarts != null) {
            if (extraStarts.size() > MAX_EXTRA_STARTS) {
                errors.put("extraStarts", "At most " + MAX_EXTRA_STARTS + " extra starts.");
            } else {
                for (int i = 0; i < extraStarts.size(); i++) {
                    LocalDateTime extra = parseExtra(extraStarts.get(i));
                    if (extra == null) {
                        errors.put("extraStarts[" + i + "]", "Use a date and time like 2026-12-24T15:00.");
                    } else if (skips.contains(extra.toLocalDate())) {
                        errors.put("extraStarts[" + i + "]", "That day is also in skipDates. Keep one of the two.");
                    } else {
                        extras.add(extra);
                    }
                }
            }
        }
        if (errors.size() > before) {
            return null;
        }
        return new SeriesSchedule(days, times, durationMinutes, startDate, endDate, skips, extras);
    }

    /** The local starts in [{@code from}, {@code to}), soonest first, each once. */
    public List<LocalDateTime> startsBetween(LocalDateTime from, LocalDateTime to) {
        TreeSet<LocalDateTime> starts = new TreeSet<>();
        if (from.isBefore(to)) {
            LocalDate first = from.toLocalDate().isAfter(startDate) ? from.toLocalDate() : startDate;
            LocalDate last = to.toLocalDate();
            if (endDate != null && endDate.isBefore(last)) {
                last = endDate;
            }
            for (LocalDate day = first; !day.isAfter(last); day = day.plusDays(1)) {
                if (!days.contains(day.getDayOfWeek()) || skipDates.contains(day)) {
                    continue;
                }
                for (LocalTime time : startTimes) {
                    LocalDateTime start = day.atTime(time);
                    if (!start.isBefore(from) && start.isBefore(to)) {
                        starts.add(start);
                    }
                }
            }
            for (LocalDateTime extra : extraStarts) {
                if (!extra.isBefore(from) && extra.isBefore(to)) {
                    starts.add(extra);
                }
            }
        }
        return List.copyOf(starts);
    }

    /** The first start at or after {@code from}, looking up to two years ahead. */
    public Optional<LocalDateTime> firstStartFrom(LocalDateTime from) {
        LocalDateTime horizon = from.plusDays(FIRST_START_SEARCH_DAYS);
        // A month at a time: the first month that has a start ends the search.
        for (LocalDateTime cursor = from; cursor.isBefore(horizon); cursor = cursor.plusDays(31)) {
            LocalDateTime next = cursor.plusDays(31).isAfter(horizon) ? horizon : cursor.plusDays(31);
            List<LocalDateTime> starts = startsBetween(cursor, next);
            if (!starts.isEmpty()) {
                return Optional.of(starts.get(0));
            }
        }
        return Optional.empty();
    }

    public Duration duration() {
        return Duration.ofMinutes(durationMinutes);
    }

    /** The days as stored: {@code MONDAY}..{@code SUNDAY}, in week order. */
    public List<String> storedDays() {
        return days.stream().map(DayOfWeek::name).toList();
    }

    public List<String> storedStartTimes() {
        return startTimes.stream().map(TIME::format).toList();
    }

    public List<String> storedSkipDates() {
        return new TreeSet<>(skipDates).stream().map(LocalDate::toString).toList();
    }

    public List<String> storedExtraStarts() {
        return extraStarts.stream().map(EXTRA::format).toList();
    }

    static LocalTime parseTime(String raw) {
        if (raw == null || !raw.trim().matches("\\d{2}:\\d{2}")) {
            return null;
        }
        try {
            return LocalTime.parse(raw.trim(), TIME);
        } catch (DateTimeException e) {
            return null;
        }
    }

    static LocalDate parseDate(String raw) {
        if (raw == null || !raw.trim().matches("\\d{4}-\\d{2}-\\d{2}")) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeException e) {
            return null;
        }
    }

    static LocalDateTime parseExtra(String raw) {
        if (raw == null || !raw.trim().matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}")) {
            return null;
        }
        try {
            return LocalDateTime.parse(raw.trim(), EXTRA);
        } catch (DateTimeException e) {
            return null;
        }
    }
}
