package com.innbucks.eventservice.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The starts a series' schedule makes (TIC-70): days, times, the first and
 * last day, skipped days and extra starts, all as the organizer typed them.
 */
class SeriesScheduleTest {

    // 2026-12-04 is a Friday.
    private static final LocalDate FIRST = LocalDate.of(2026, 12, 4);

    private static SeriesSchedule schedule(List<String> days, List<String> times, LocalDate start, LocalDate end,
                                           List<String> skips, List<String> extras) {
        Map<String, String> errors = new LinkedHashMap<>();
        SeriesSchedule schedule = SeriesSchedule.parse(days, times, 120, start, end, skips, extras, errors);
        assertThat(errors).as("a valid schedule").isEmpty();
        return schedule;
    }

    private static Map<String, String> errorsOf(List<String> days, List<String> times, int minutes, LocalDate start,
                                                LocalDate end, List<String> skips, List<String> extras) {
        Map<String, String> errors = new LinkedHashMap<>();
        assertThat(SeriesSchedule.parse(days, times, minutes, start, end, skips, extras, errors)).isNull();
        return errors;
    }

    @Test
    void everyTimeOnEveryListedDay_inOrder_fromTheFirstDayToTheLast() {
        SeriesSchedule s = schedule(List.of("FRIDAY", "saturday"), List.of("21:30", "19:00"), FIRST,
                FIRST.plusDays(8), List.of(), List.of());

        assertThat(s.startsBetween(FIRST.minusDays(10).atStartOfDay(), FIRST.plusDays(30).atStartOfDay()))
                .containsExactly(
                        LocalDateTime.of(2026, 12, 4, 19, 0), LocalDateTime.of(2026, 12, 4, 21, 30),
                        LocalDateTime.of(2026, 12, 5, 19, 0), LocalDateTime.of(2026, 12, 5, 21, 30),
                        LocalDateTime.of(2026, 12, 11, 19, 0), LocalDateTime.of(2026, 12, 11, 21, 30),
                        LocalDateTime.of(2026, 12, 12, 19, 0), LocalDateTime.of(2026, 12, 12, 21, 30));
    }

    @Test
    void theWindowIsHalfOpen_andAStartBeforeItIsLeftOut() {
        SeriesSchedule s = schedule(List.of("FRIDAY"), List.of("19:00", "21:30"), FIRST, null, List.of(), List.of());

        assertThat(s.startsBetween(LocalDateTime.of(2026, 12, 4, 19, 1), LocalDateTime.of(2026, 12, 11, 19, 0)))
                .as("19:00 has passed; the next 19:00 is the window's end, not in it")
                .containsExactly(LocalDateTime.of(2026, 12, 4, 21, 30));
    }

    @Test
    void aSkippedDayHasNoStart_andAnExtraStartIsAddedWhateverTheDays() {
        SeriesSchedule s = schedule(List.of("FRIDAY"), List.of("19:00"), FIRST, FIRST.plusDays(21),
                List.of("2026-12-25"), List.of("2026-12-24T15:00", "2027-01-30T10:00"));

        assertThat(s.startsBetween(FIRST.atStartOfDay(), LocalDate.of(2027, 2, 1).atStartOfDay())).containsExactly(
                LocalDateTime.of(2026, 12, 4, 19, 0),
                LocalDateTime.of(2026, 12, 11, 19, 0),
                LocalDateTime.of(2026, 12, 18, 19, 0),
                LocalDateTime.of(2026, 12, 24, 15, 0),
                LocalDateTime.of(2027, 1, 30, 10, 0));
    }

    @Test
    void anExtraStartOnARegularStart_countsOnce() {
        SeriesSchedule s = schedule(List.of("FRIDAY"), List.of("19:00"), FIRST, FIRST,
                List.of(), List.of("2026-12-04T19:00"));

        assertThat(s.startsBetween(FIRST.atStartOfDay(), FIRST.plusDays(1).atStartOfDay()))
                .containsExactly(LocalDateTime.of(2026, 12, 4, 19, 0));
    }

    @Test
    void theFirstStart_isFoundFromAnyMoment_evenMonthsAhead() {
        SeriesSchedule s = schedule(List.of("SUNDAY"), List.of("08:00"), LocalDate.of(2027, 6, 1), null,
                List.of(), List.of());

        assertThat(s.firstStartFrom(LocalDateTime.of(2026, 11, 26, 9, 0)))
                .contains(LocalDateTime.of(2027, 6, 6, 8, 0));
    }

    @Test
    void aScheduleWhoseLastDayHasPassed_hasNoFirstStart() {
        SeriesSchedule s = schedule(List.of("FRIDAY"), List.of("19:00"), LocalDate.of(2026, 1, 2),
                LocalDate.of(2026, 3, 27), List.of(), List.of());

        assertThat(s.firstStartFrom(LocalDateTime.of(2026, 11, 26, 9, 0))).isEmpty();
    }

    @Test
    void storedForms_areSortedAndCountEachValueOnce() {
        SeriesSchedule s = schedule(List.of("SUNDAY", "FRIDAY", "friday"), List.of("21:30", "19:00", "19:00"),
                FIRST, null, List.of("2027-01-01", "2026-12-25"), List.of("2026-12-31T22:00", "2026-12-24T15:00"));

        assertThat(s.storedDays()).containsExactly("FRIDAY", "SUNDAY");
        assertThat(s.storedStartTimes()).containsExactly("19:00", "21:30");
        assertThat(s.storedSkipDates()).containsExactly("2026-12-25", "2027-01-01");
        assertThat(s.storedExtraStarts()).containsExactly("2026-12-24T15:00", "2026-12-31T22:00");
    }

    @Test
    void eachBadValue_isNamedByItsPlace() {
        Map<String, String> errors = errorsOf(List.of("FRIDAY", "Fryday"), List.of("19:00", "7pm", "24:00"), 0,
                FIRST, FIRST.minusDays(1), List.of("2026-02-30"), List.of("2026-12-24 15:00"));

        assertThat(errors).containsOnlyKeys("daysOfWeek[1]", "startTimes[1]", "startTimes[2]", "durationMinutes",
                "endDate", "skipDates[0]", "extraStarts[0]");
        assertThat(errors.get("startTimes[1]")).isEqualTo("Use the 24-hour time, like 19:00.");
        assertThat(errors.get("skipDates[0]")).as("30 February is refused, not moved").isEqualTo(
                "Use a date like 2026-12-25.");
    }

    @Test
    void noDays_noTimes_tooManyTimes_andAnExtraOnASkippedDay_areRefused() {
        assertThat(errorsOf(null, List.of(), 60, FIRST, null, null, null))
                .containsOnlyKeys("daysOfWeek", "startTimes");
        List<String> eleven = List.of("08:00", "09:00", "10:00", "11:00", "12:00", "13:00", "14:00", "15:00",
                "16:00", "17:00", "18:00");
        assertThat(errorsOf(List.of("MONDAY"), eleven, 60, FIRST, null, null, null))
                .containsEntry("startTimes", "At most 10 start times a day.");
        assertThat(errorsOf(List.of("MONDAY"), List.of("19:00"), 60, FIRST, null, List.of("2026-12-24"),
                List.of("2026-12-24T15:00")))
                .containsEntry("extraStarts[0]", "That day is also in skipDates. Keep one of the two.");
        assertThat(errorsOf(List.of("MONDAY"), List.of("19:00"), 60, null, null, null, null))
                .containsOnlyKeys("startDate");
    }
}
