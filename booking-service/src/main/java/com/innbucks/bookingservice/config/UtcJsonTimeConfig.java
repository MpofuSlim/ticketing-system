package com.innbucks.bookingservice.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

/**
 * Wire-format rule: every {@code LocalDateTime} this service serializes is
 * UTC (see CLAUDE.md — containers pin UTC, code uses
 * {@code LocalDateTime.now(ZoneOffset.UTC)}), so JSON output carries the
 * explicit {@code Z} designator: {@code 2026-07-27T07:19:00Z}. Without it,
 * every consumer guesses the zone — browsers guess "local", which showed
 * Harare users times two hours behind.
 *
 * <p>Inbound stays permissive: {@code Z}-suffixed, {@code ±HH:mm}-offset
 * (normalized to UTC wall-clock, since our LocalDateTimes MEAN UTC), and
 * legacy zoneless strings all parse — so older sibling services, in-flight
 * FE code, and stored requests keep working through a rolling deploy.
 * Field-level {@code @JsonDeserialize} annotations still win where present.
 *
 * <p>This is the wire-format half of the LocalDateTime→Instant migration;
 * the column migration ({@code timestamptz}) can now follow later without
 * any further wire change.
 */
@Configuration
public class UtcJsonTimeConfig {

    /** Fixed-shape output (always seconds, no nanos): {@code yyyy-MM-dd'T'HH:mm:ss'Z'}. */
    private static final DateTimeFormatter UTC_WIRE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");

    @Bean
    public Module utcLocalDateTimeModule() {
        SimpleModule module = new SimpleModule("utc-local-date-time");
        module.addSerializer(LocalDateTime.class, new UtcLocalDateTimeSerializer());
        module.addDeserializer(LocalDateTime.class, new FlexibleUtcLocalDateTimeDeserializer());
        return module;
    }

    static final class UtcLocalDateTimeSerializer extends JsonSerializer<LocalDateTime> {
        @Override
        public void serialize(LocalDateTime value, JsonGenerator gen, SerializerProvider serializers)
                throws IOException {
            gen.writeString(UTC_WIRE.format(value.truncatedTo(ChronoUnit.SECONDS)));
        }
    }

    static final class FlexibleUtcLocalDateTimeDeserializer extends JsonDeserializer<LocalDateTime> {
        @Override
        public LocalDateTime deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            String value = p.getText();
            if (value == null || value.isBlank()) {
                return null;
            }
            try {
                // Offset-carrying form: normalize to the UTC wall-clock our
                // LocalDateTime columns store ("Z" input is a no-op shift).
                return OffsetDateTime.parse(value)
                        .withOffsetSameInstant(ZoneOffset.UTC)
                        .toLocalDateTime();
            } catch (DateTimeParseException ignored) {
                return LocalDateTime.parse(value);
            }
        }
    }
}
