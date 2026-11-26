package com.innbucks.eventservice.entity;

/**
 * What an event is to its series ({@code events.series_state}, V24). Plain
 * string constants rather than an enum, like the column's CHECK: a row read
 * back always hydrates, whatever a later image stores.
 *
 * <ul>
 *   <li>{@link #TEMPLATE}: the series' set-up. The organizer edits it with the
 *       ordinary event endpoints; it is never published, never bookable and
 *       never listed.</li>
 *   <li>{@link #GENERATING}: a date the generator has started and not
 *       finished (its seat layout and questions are still being copied).
 *       Hidden like the template.</li>
 *   <li>{@link #READY}: a finished date, an ordinary event in every way.</li>
 * </ul>
 */
public final class SeriesState {

    public static final String TEMPLATE = "TEMPLATE";
    public static final String GENERATING = "GENERATING";
    public static final String READY = "READY";

    private SeriesState() {
    }

    public static boolean isTemplate(Event event) {
        return event != null && TEMPLATE.equals(event.getSeriesState());
    }

    /** A one-off event or a finished date: shown and used like any event. */
    public static boolean isListed(Event event) {
        return event != null && (event.getSeriesState() == null || READY.equals(event.getSeriesState()));
    }
}
