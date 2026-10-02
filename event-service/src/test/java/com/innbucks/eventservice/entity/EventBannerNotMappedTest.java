package com.innbucks.eventservice.entity;

import jakarta.persistence.Column;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins that the banner BYTES are not a mapped field of {@link Event}.
 *
 * <p>They used to be, as a {@code @Basic(fetch = LAZY)} byte[]. That is only
 * lazy with Hibernate bytecode enhancement, which event-service does not run, so
 * every query loading an Event, every list page included, read up to 10 MB of
 * image per event. The bytes now go through {@code EventRepository}'s banner
 * queries only. Mapping them back, under any field name, silently restores the
 * cost: nothing fails and every page gets slower.
 */
class EventBannerNotMappedTest {

    @Test
    void noFieldOfEventIsBoundToTheBannerImageColumn() {
        List<String> bound = Arrays.stream(Event.class.getDeclaredFields())
                .filter(f -> {
                    Column c = f.getAnnotation(Column.class);
                    return c != null && "banner_image".equalsIgnoreCase(c.name());
                })
                .map(Field::getName)
                .toList();
        assertThat(bound).as("fields mapped to events.banner_image").isEmpty();
    }

    @Test
    void eventHasNoByteArrayField() {
        // Catches the same mapping under an implicit column name (a field named
        // bannerImage with no @Column maps to banner_image by naming strategy).
        List<String> byteArrays = Arrays.stream(Event.class.getDeclaredFields())
                .filter(f -> f.getType() == byte[].class)
                .map(Field::getName)
                .toList();
        assertThat(byteArrays).as("byte[] fields on Event").isEmpty();
    }
}
