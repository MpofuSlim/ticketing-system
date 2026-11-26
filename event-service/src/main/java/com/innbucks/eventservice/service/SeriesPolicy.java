package com.innbucks.eventservice.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Whether recurring events are switched on in this cell ({@code events.series.*},
 * V24, TIC-70) and how much one generator run may do.
 *
 * <ul>
 *   <li><b>Off</b> (the default): {@code /events/series} answers 404 and the
 *       generator does nothing. Existing events are untouched.</li>
 *   <li><b>On:</b> organizers set up series, and the generator makes their
 *       dates inside each series' booking window.</li>
 * </ul>
 */
@Component
@Slf4j
public class SeriesPolicy {

    private final boolean enabled;
    private final int maxDatesPerRun;
    private final Duration runBudget;

    public SeriesPolicy(@Value("${events.series.enabled:false}") boolean enabled,
                        @Value("${events.series.max-dates-per-run:100}") int maxDatesPerRun,
                        @Value("${events.series.run-budget:PT25S}") Duration runBudget) {
        this.enabled = enabled;
        this.maxDatesPerRun = maxDatesPerRun > 0 ? maxDatesPerRun : 100;
        this.runBudget = runBudget == null || runBudget.isNegative() || runBudget.isZero()
                ? Duration.ofSeconds(25) : runBudget;
        log.info("Recurring event series are {}", enabled
                ? "ON: organizers can set up a series and its dates are made automatically"
                : "OFF: /events/series answers 404 and no dates are made");
    }

    public boolean enabled() {
        return enabled;
    }

    public int maxDatesPerRun() {
        return maxDatesPerRun;
    }

    public Duration runBudget() {
        return runBudget;
    }
}
