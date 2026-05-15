package com.innbucks.eventservice.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

/**
 * Accepts ISO-8601 datetime strings with or without a timezone designator.
 * Browser code commonly sends UTC-suffixed strings (e.g. "2026-05-06T14:00:00.000Z"
 * via Date.toISOString()), which Jackson's stock LocalDateTime deserializer
 * rejects — leaving the field null and silently dropping the update. This
 * variant tries OffsetDateTime first (handles Z and ±HH:mm offsets) and falls
 * back to a plain LocalDateTime parse. The wall-clock time is preserved
 * exactly as sent; we do not shift between timezones.
 */
public class FlexibleLocalDateTimeDeserializer extends JsonDeserializer<LocalDateTime> {

    @Override
    public LocalDateTime deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        String value = p.getText();
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            return LocalDateTime.parse(value);
        }
    }
}
