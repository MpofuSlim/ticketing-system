package com.innbucks.bookingservice.config;

import feign.RequestInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Stamps {@link WireAudience#HEADER} on every outbound Feign call so the
 * service answering it knows a machine is reading, and keeps its timestamps in
 * {@code Z} rather than the market offset.
 *
 * <p>The {@code /internal/} path convention already covers endpoints built for
 * S2S, but not a sibling service calling a public one — booking-service fetches
 * {@code GET /events/{id}}, whose response carries {@code startDateTime}. Path
 * alone would hand that to us at {@code +02:00}. Our own deserializer would
 * normalize it back, so nothing would visibly break; the marker is here so the
 * S2S contract holds by construction instead of by luck, for consumers that are
 * stricter than we are.
 *
 * <p>Applies to all four Feign clients (seat, event, user, loyalty) — Spring
 * Cloud OpenFeign picks up {@link RequestInterceptor} beans globally.
 */
@Configuration
public class S2sMarkerFeignInterceptor {

    @Bean
    public RequestInterceptor s2sMarkerRequestInterceptor() {
        return template -> template.header(WireAudience.HEADER, "1");
    }
}
