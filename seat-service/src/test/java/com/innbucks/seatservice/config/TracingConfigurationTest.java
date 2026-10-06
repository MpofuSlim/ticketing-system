package com.innbucks.seatservice.config;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration;
import org.springframework.boot.micrometer.tracing.autoconfigure.MicrometerTracingAutoConfiguration;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryEventPublisherBeansApplicationListener;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingAutoConfiguration;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.otlp.OtlpTracingAutoConfiguration;
import org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Tracing wiring for this service (CLAUDE.md "Tracing and compression"), on
 * the REAL auto-configuration and this module's REAL application.yaml — no
 * database, no web server, so it runs everywhere:
 * <ul>
 *   <li>no exporter while no collector is named, 10% sampling;</li>
 *   <li>traceId/spanId in the MDC (what both log layouts print);</li>
 *   <li>the RestClient.Builder bean is observed and sends traceparent to a
 *       fleet service — and nothing to a partner host, even through that
 *       observed builder.</li>
 * </ul>
 */
class TracingConfigurationTest {

    private static final String TRACEPARENT = "00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}";

    @Configuration(proxyBeanMethods = false)
    static class AnInternalClientBuilder {
        @Bean
        RestClient.Builder restClientBuilder() {
            return RestClient.builder();
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            // What SpringApplication does through spring.factories: hook the
            // OTel context storage so scope changes reach the MDC listener.
            .withInitializer(context -> {
                OpenTelemetryEventPublisherBeansApplicationListener.addWrapper();
                context.addApplicationListener(new OpenTelemetryEventPublisherBeansApplicationListener());
            })
            .withConfiguration(AutoConfigurations.of(
                    ObservationAutoConfiguration.class,
                    OpenTelemetrySdkAutoConfiguration.class,
                    OpenTelemetryTracingAutoConfiguration.class,
                    OtlpTracingAutoConfiguration.class,
                    MicrometerTracingAutoConfiguration.class))
            .withUserConfiguration(TracingConfig.class, AnInternalClientBuilder.class);

    @Test
    void noCollector_noExporter_tenPercentSampling() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(Tracer.class);
            assertThat(context).doesNotHaveBean(SpanExporter.class);
            assertThat(context.getEnvironment().getProperty("management.tracing.sampling.probability"))
                    .isEqualTo("0.1");
            assertThat(context.getEnvironment().containsProperty("management.otlp.tracing.endpoint")).isFalse();
        });
    }

    @Test
    void traceAndSpanIds_areInTheMdc_insideAnObservation() {
        runner.run(context -> {
            ObservationRegistry registry = context.getBean(ObservationRegistry.class);
            Map<String, String> mdc = Observation.createNotStarted("test", registry)
                    .observe(() -> new HashMap<>(MDC.getCopyOfContextMap()));

            assertThat(mdc.get("traceId")).matches("[0-9a-f]{32}");
            assertThat(mdc.get("spanId")).matches("[0-9a-f]{16}");
            assertThat(MDC.get("traceId")).as("cleared when the scope closes").isNull();
        });
    }

    @Test
    void theBuilderBean_sendsTraceparent_toAFleetService() {
        runner.run(context -> {
            RestClient.Builder builder = context.getBean(RestClient.Builder.class);
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            server.expect(requestTo("http://event-service/events/internal/ping"))
                    .andExpect(header("traceparent", org.hamcrest.Matchers.matchesPattern(TRACEPARENT)))
                    .andRespond(withSuccess());

            builder.build().get().uri("http://event-service/events/internal/ping").retrieve().toBodilessEntity();
            server.verify();
        });
    }

    @Test
    void theSameObservedBuilder_sendsNothing_toAPartner() {
        runner.run(context -> {
            RestClient.Builder builder = context.getBean(RestClient.Builder.class);
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            server.expect(requestTo("https://notify.innbucks.co.zw/api/sms"))
                    .andExpect(headerDoesNotExist("traceparent"))
                    .andExpect(headerDoesNotExist("tracestate"))
                    .andExpect(headerDoesNotExist("baggage"))
                    .andRespond(withSuccess());

            builder.build().get().uri("https://notify.innbucks.co.zw/api/sms").retrieve().toBodilessEntity();
            server.verify();
        });
    }

    @Test
    void thePropagatorBean_isTheFleetOnlyOne() {
        runner.run(context -> assertThat(context.getBean(Propagator.class))
                .isInstanceOf(FleetOnlyTracePropagator.class));
    }

    @Test
    void fleetHost_isASingleLabel() {
        assertThat(FleetOnlyTracePropagator.isFleetHost("booking-service")).isTrue();
        assertThat(FleetOnlyTracePropagator.isFleetHost("localhost")).isTrue();
        assertThat(FleetOnlyTracePropagator.isFleetHost("payonline.econet.co.zw")).isFalse();
        assertThat(FleetOnlyTracePropagator.isFleetHost("10.0.146.246")).isFalse();
        assertThat(FleetOnlyTracePropagator.isFleetHost("::1")).isFalse();
        assertThat(FleetOnlyTracePropagator.isFleetHost(null)).isFalse();
        assertThat(FleetOnlyTracePropagator.hostOf("not a request")).isNull();
        ClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET, URI.create("http://user-service/x"));
        assertThat(FleetOnlyTracePropagator.hostOf(request)).isEqualTo("user-service");
    }
}
