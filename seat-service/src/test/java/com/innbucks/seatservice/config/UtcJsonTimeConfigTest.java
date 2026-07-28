package com.innbucks.seatservice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the UTC wire format: every serialized LocalDateTime carries the
 * explicit Z designator (fixed yyyy-MM-dd'T'HH:mm:ss'Z' shape, nanos
 * truncated), and inbound parsing accepts Z-suffixed, offset-carrying
 * (normalized to UTC) and legacy zoneless strings — the rolling-deploy /
 * FE-compatibility guarantee.
 *
 * <p>Covers BOTH Jackson stacks: the Jackson 3 mapper is the one Boot 4's
 * HTTP converter uses (the one that fixes the browser display), the
 * Jackson 2 mapper serves the legacy/jjwt path. The Jackson 3 cases exist
 * because the first ship registered only the Jackson 2 module and the HTTP
 * wire silently kept the zoneless format.
 */
class UtcJsonTimeConfigTest {

    private ObjectMapper mapper;
    private tools.jackson.databind.json.JsonMapper mapper3;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        // Same module beans Boot registers into the auto-configured mappers.
        mapper.registerModule(new UtcJsonTimeConfig().utcLocalDateTimeModule());
        mapper3 = tools.jackson.databind.json.JsonMapper.builder()
                .addModule(new UtcJsonTimeConfig().utcLocalDateTimeJackson3Module())
                .build();
    }

    @Test
    void jackson3_httpMapper_serializesWithExplicitUtcDesignator() {
        assertThat(mapper3.writeValueAsString(LocalDateTime.of(2026, 7, 27, 7, 19, 3)))
                .isEqualTo("\"2026-07-27T07:19:03Z\"");
    }

    @Test
    void jackson3_httpMapper_deserializesAllThreeForms() {
        assertThat(mapper3.readValue("\"2026-07-27T07:19:03Z\"", LocalDateTime.class))
                .isEqualTo(LocalDateTime.of(2026, 7, 27, 7, 19, 3));
        assertThat(mapper3.readValue("\"2026-07-27T09:19:03+02:00\"", LocalDateTime.class))
                .isEqualTo(LocalDateTime.of(2026, 7, 27, 7, 19, 3));
        assertThat(mapper3.readValue("\"2026-07-27T07:19:03\"", LocalDateTime.class))
                .isEqualTo(LocalDateTime.of(2026, 7, 27, 7, 19, 3));
    }

    @Test
    void serializes_withExplicitUtcDesignator() throws Exception {
        assertThat(mapper.writeValueAsString(LocalDateTime.of(2026, 7, 27, 7, 19, 3)))
                .isEqualTo("\"2026-07-27T07:19:03Z\"");
    }

    @Test
    void serializes_zeroSecondsAndNanos_keepFixedShape() throws Exception {
        assertThat(mapper.writeValueAsString(LocalDateTime.of(2026, 7, 27, 7, 19, 0, 123456789)))
                .isEqualTo("\"2026-07-27T07:19:00Z\"");
    }

    @Test
    void deserializes_zSuffixed() throws Exception {
        assertThat(mapper.readValue("\"2026-07-27T07:19:03Z\"", LocalDateTime.class))
                .isEqualTo(LocalDateTime.of(2026, 7, 27, 7, 19, 3));
    }

    @Test
    void deserializes_offsetForm_normalizedToUtc() throws Exception {
        // 09:19 at +02:00 IS 07:19 UTC — our columns store UTC wall-clock.
        assertThat(mapper.readValue("\"2026-07-27T09:19:03+02:00\"", LocalDateTime.class))
                .isEqualTo(LocalDateTime.of(2026, 7, 27, 7, 19, 3));
    }

    @Test
    void deserializes_legacyZoneless() throws Exception {
        assertThat(mapper.readValue("\"2026-07-27T07:19:03\"", LocalDateTime.class))
                .isEqualTo(LocalDateTime.of(2026, 7, 27, 7, 19, 3));
    }

    @Test
    void roundTrip_isLossless() throws Exception {
        LocalDateTime original = LocalDateTime.of(2026, 7, 27, 7, 19, 3);
        String wire = mapper.writeValueAsString(original);
        assertThat(mapper.readValue(wire, LocalDateTime.class)).isEqualTo(original);
    }
}
