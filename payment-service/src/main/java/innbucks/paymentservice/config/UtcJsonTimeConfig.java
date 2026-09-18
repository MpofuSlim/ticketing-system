package innbucks.paymentservice.config;

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
 * Wire-format rule (CLAUDE.md): the BE renders, the FE parses nothing. Every
 * {@code LocalDateTime} this service stores is UTC — containers pin UTC, code
 * uses {@code LocalDateTime.now(ZoneOffset.UTC)} — and what goes on the wire
 * depends on who reads it:
 *
 * <ul>
 *   <li><b>A person</b> gets the MARKET OFFSET,
 *       {@code 2026-07-27T09:19:00+02:00}, so the client prints the string
 *       verbatim and never converts. A ZW cell shows Harare time because we
 *       resolved Harare, not because the reader's device happened to be there.
 *   <li><b>Another service</b> gets {@code Z}, {@code 2026-07-27T07:19:00Z} —
 *       a per-cell offset in an S2S payload just invites double-conversion.
 * </ul>
 *
 * <p>Same instant either way. {@link WireAudience} decides which, per request,
 * because the two surfaces share DTO classes. Emitting neither designator was
 * the original bug: consumers guessed, browsers guessed "local", and Harare
 * users saw times two hours behind.
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
 *
 * <p><b>Two Jackson stacks, two module beans.</b> Boot 4 serves HTTP JSON
 * with Jackson 3 ({@code tools.jackson} — its auto-config collects
 * {@link tools.jackson.databind.JacksonModule} beans), while the legacy
 * Jackson 2 mapper ({@code spring-boot-jackson2}, kept for jjwt + existing
 * code) collects {@link Module} beans. Registering only the Jackson 2
 * module changes NOTHING on the HTTP wire — that was this config's first
 * shipped bug — so both are registered and both are pinned by
 * {@code UtcJsonTimeConfigTest}.
 */
@Configuration
public class UtcJsonTimeConfig {

    /** Fixed-shape output (always seconds, no nanos): {@code yyyy-MM-dd'T'HH:mm:ss'Z'}. */
    private static final DateTimeFormatter UTC_WIRE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");

    /**
     * Human-facing shape, same precision with an explicit offset:
     * {@code yyyy-MM-dd'T'HH:mm:ss+02:00}. Still unambiguous ISO-8601, but the
     * leading characters are the market's wall clock, so a client prints them
     * verbatim and does no arithmetic.
     */
    private static final DateTimeFormatter MARKET_WIRE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    /**
     * Jackson 3 module — the one the HTTP message converter actually uses, and
     * therefore the only one that decides what a browser sees. Market rendering
     * lives here and nowhere else: the Jackson 2 module below still emits
     * {@code Z} for Feign request bodies, jjwt and anything else off the
     * response path.
     */
    @Bean
    public tools.jackson.databind.JacksonModule utcLocalDateTimeJackson3Module(MarketTimeZone marketTimeZone) {
        tools.jackson.databind.module.SimpleModule module =
                new tools.jackson.databind.module.SimpleModule("utc-local-date-time-j3");
        module.addSerializer(LocalDateTime.class, new AudienceAwareLocalDateTimeSerializerJ3(marketTimeZone));
        module.addDeserializer(LocalDateTime.class, new FlexibleUtcLocalDateTimeDeserializerJ3());
        return module;
    }

    /** Jackson 2 module — the legacy mapper (jjwt, Feign, existing test code). */
    @Bean
    public Module utcLocalDateTimeModule() {
        SimpleModule module = new SimpleModule("utc-local-date-time");
        module.addSerializer(LocalDateTime.class, new UtcLocalDateTimeSerializer());
        module.addDeserializer(LocalDateTime.class, new FlexibleUtcLocalDateTimeDeserializer());
        return module;
    }

    /**
     * Renders at the market offset for a person, {@code Z} for a service.
     *
     * <p>The audience is a property of the request, not of the value — the same
     * DTO class is served on both surfaces — so it is resolved per call via
     * {@link WireAudience}. That lookup is thread-bound to the request being
     * serialized, which is the thread Jackson writes on.
     */
    static final class AudienceAwareLocalDateTimeSerializerJ3 extends tools.jackson.databind.ValueSerializer<LocalDateTime> {

        private final MarketTimeZone marketTimeZone;

        AudienceAwareLocalDateTimeSerializerJ3(MarketTimeZone marketTimeZone) {
            this.marketTimeZone = marketTimeZone;
        }

        @Override
        public void serialize(LocalDateTime value, tools.jackson.core.JsonGenerator gen,
                              tools.jackson.databind.SerializationContext ctxt) {
            LocalDateTime seconds = value.truncatedTo(ChronoUnit.SECONDS);
            gen.writeString(WireAudience.isServiceToService()
                    ? UTC_WIRE.format(seconds)
                    : MARKET_WIRE.format(marketTimeZone.atMarketFromUtc(seconds)));
        }
    }

    static final class FlexibleUtcLocalDateTimeDeserializerJ3 extends tools.jackson.databind.ValueDeserializer<LocalDateTime> {
        @Override
        public LocalDateTime deserialize(tools.jackson.core.JsonParser p,
                                         tools.jackson.databind.DeserializationContext ctxt) {
            String value = p.getString();
            if (value == null || value.isBlank()) {
                return null;
            }
            try {
                return OffsetDateTime.parse(value)
                        .withOffsetSameInstant(ZoneOffset.UTC)
                        .toLocalDateTime();
            } catch (DateTimeParseException ignored) {
                return LocalDateTime.parse(value);
            }
        }
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
