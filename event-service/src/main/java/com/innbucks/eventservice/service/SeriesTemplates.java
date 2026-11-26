package com.innbucks.eventservice.service;

import com.innbucks.eventservice.entity.Event;
import com.innbucks.eventservice.entity.EventSeries;
import com.innbucks.eventservice.entity.SeriesState;
import com.innbucks.eventservice.repository.EventSeriesRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * What a series template's lifecycle does to its series (V24, TIC-70). A series
 * is published, reviewed, unpublished, rejected and deleted through its
 * template with the ordinary event endpoints; {@link EventService} calls these
 * in the same transaction where the event itself would have gone live or
 * stopped. The template itself never goes live.
 *
 * <p>Only NEW dates follow the series: a date already made is an ordinary
 * event and keeps its own state. Pausing and ending a series' dates are the
 * series endpoints' (TIC-71).
 *
 * <p><b>A PAUSED series stays paused</b> when its template is published,
 * approved, unpublished or rejected: the pause is the organizer's explicit
 * choice, and only resume or end takes a series out of it. Ending puts back
 * the sales the pause closed, so the dates that remain keep selling as before.
 */
@Component
@Slf4j
public class SeriesTemplates {

    private final EventSeriesRepository seriesRepository;
    private final SeriesPauses pauses;

    @Autowired
    public SeriesTemplates(EventSeriesRepository seriesRepository, SeriesPauses pauses) {
        this.seriesRepository = seriesRepository;
        this.pauses = pauses;
    }

    /** For tests that never pause a series. */
    public SeriesTemplates(EventSeriesRepository seriesRepository) {
        this(seriesRepository, null);
    }

    /** Published by its organizer or approved by staff: the series makes dates. */
    public void published(Event template) {
        move(template, EventSeries.Status.ACTIVE);
    }

    /** Unpublished or rejected: the series makes no new dates. */
    public void unpublished(Event template) {
        move(template, EventSeries.Status.DRAFT);
    }

    /** Deleted: the series ends for good. */
    public void ended(Event template) {
        move(template, EventSeries.Status.ENDED);
    }

    private void move(Event template, EventSeries.Status to) {
        if (!SeriesState.isTemplate(template) || template.getSeriesId() == null) {
            return;
        }
        seriesRepository.findById(template.getSeriesId()).ifPresent(series -> {
            EventSeries.Status from = series.getStatus();
            if (from == to || from == EventSeries.Status.ENDED) {
                return;
            }
            if (from == EventSeries.Status.PAUSED && to != EventSeries.Status.ENDED) {
                log.info("Event series stays paused seriesId={} templateEventId={} wouldMoveTo={}",
                        series.getSeriesId(), template.getEventId(), to);
                return;
            }
            if (from == EventSeries.Status.PAUSED && pauses != null) {
                pauses.restore(series.getSeriesId());
            }
            series.setStatus(to);
            seriesRepository.save(series);
            log.info("Event series moved seriesId={} templateEventId={} from={} to={}",
                    series.getSeriesId(), template.getEventId(), from, to);
        });
    }
}
