package com.innbucks.eventservice.service;

import com.innbucks.eventservice.entity.Event;
import com.innbucks.eventservice.entity.EventSeries;
import com.innbucks.eventservice.entity.SeriesState;
import com.innbucks.eventservice.repository.EventSeriesRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * What a series template's lifecycle does to its series (V24, TIC-70). A series
 * is published, reviewed, unpublished, rejected and deleted through its
 * template with the ordinary event endpoints; {@link EventService} calls these
 * in the same transaction where the event itself would have gone live or
 * stopped. The template itself never goes live.
 *
 * <p>Only NEW dates follow the series: a date already made is an ordinary
 * event and keeps its own state (pausing and ending its dates is S2).
 */
@Component
@Slf4j
public class SeriesTemplates {

    private final EventSeriesRepository seriesRepository;

    public SeriesTemplates(EventSeriesRepository seriesRepository) {
        this.seriesRepository = seriesRepository;
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
            series.setStatus(to);
            seriesRepository.save(series);
            log.info("Event series moved seriesId={} templateEventId={} from={} to={}",
                    series.getSeriesId(), template.getEventId(), from, to);
        });
    }
}
