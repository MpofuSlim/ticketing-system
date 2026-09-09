package com.innbucks.eventservice.entity;

import com.innbucks.eventservice.controller.EventController;
import com.innbucks.eventservice.dto.EventCategoryOptionDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the three-way agreement the category vocabulary lives or dies by:
 * the {@link EventCategory} enum, the DB CHECK constraint the latest
 * migration maintains, and the {@code VARCHAR} width. V10's lesson — the
 * constraint was created in V4 with a fixed list, so any enum addition that
 * forgets the migration makes every create of that category fail at the DB
 * layer, in production, after a green build. This test turns that into a
 * red build instead.
 */
class EventCategoryVocabularyTest {

    /** The migration that currently owns chk_events_category's value list. */
    private static final String CONSTRAINT_MIGRATION = "/db/migration/V12__event_category_vocabulary.sql";
    private static final int COLUMN_WIDTH = 30;

    private static String migrationSql() throws IOException {
        try (InputStream in = EventCategoryVocabularyTest.class.getResourceAsStream(CONSTRAINT_MIGRATION)) {
            assertThat(in).as("migration resource " + CONSTRAINT_MIGRATION).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("the DB CHECK constraint lists exactly the enum's values — no drift in either direction")
    void checkConstraintMatchesTheEnumExactly() throws IOException {
        Matcher check = Pattern.compile("CHECK \\(category IN \\(([^)]+)\\)\\)").matcher(migrationSql());
        assertThat(check.find()).as("CHECK (category IN (...)) present in V12").isTrue();

        Set<String> inConstraint = Arrays.stream(check.group(1).split(","))
                .map(s -> s.trim().replaceAll("^'|'$", ""))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> inEnum = Arrays.stream(EventCategory.values())
                .map(Enum::name)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        assertThat(inConstraint).isEqualTo(inEnum);
    }

    @Test
    @DisplayName("every code fits the column, and the original seven survive (rows reference them)")
    void widthAndBackCompat() {
        for (EventCategory c : EventCategory.values()) {
            assertThat(c.name().length())
                    .as("%s must fit VARCHAR(%d)", c.name(), COLUMN_WIDTH)
                    .isLessThanOrEqualTo(COLUMN_WIDTH);
            assertThat(c.getDisplayName()).as("displayName of " + c).isNotBlank();
        }
        assertThat(Arrays.stream(EventCategory.values()).map(Enum::name).collect(Collectors.toSet()))
                .contains("BOOKS", "COMEDY", "FUN_RUN", "HALF_MARATHON", "MARATHON", "CONCERT", "SPORT")
                .contains("OTHER");
    }

    @Test
    @DisplayName("GET /events/categories serves values() verbatim — code+displayName, declaration order")
    void categoriesEndpointServesTheEnumInDeclarationOrder() {
        List<EventCategoryOptionDTO> options = new EventController(null, null)
                .listCategories().getBody().getData();

        assertThat(options).hasSize(EventCategory.values().length);
        for (int i = 0; i < options.size(); i++) {
            EventCategory expected = EventCategory.values()[i];
            assertThat(options.get(i).code()).isEqualTo(expected.name());
            assertThat(options.get(i).displayName()).isEqualTo(expected.getDisplayName());
        }
    }
}
