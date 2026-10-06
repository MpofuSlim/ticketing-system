package com.innbucks.bookingservice.config;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.innbucks.tracingprobe.TraceProbeClient;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration;
import org.springframework.boot.micrometer.tracing.autoconfigure.MicrometerTracingAutoConfiguration;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryEventPublisherBeansApplicationListener;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingAutoConfiguration;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.otlp.OtlpTracingAutoConfiguration;
import org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cloud.openfeign.FeignAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Tracing wiring for booking-service (CLAUDE.md "Tracing and compression"), on
 * the REAL auto-configuration and this module's REAL application.yaml: no
 * exporter while no collector is named, traceId/spanId in the MDC, the trace
 * across both async executors, traceparent on the @FeignClient calls, and
 * nothing toward a partner host even through an observed client.
 */
class TracingConfigurationTest {

    private static final String TRACEPARENT = "00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}";

    private static WireMockServer wireMock;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMock != null) wireMock.stop();
    }

    @AfterEach
    void reset() {
        wireMock.resetAll();
        MDC.clear();
    }

    @Configuration(proxyBeanMethods = false)
    static class AnInternalClientBuilder {
        @Bean
        RestClient.Builder restClientBuilder() {
            return RestClient.builder();
        }
    }

    @Configuration(proxyBeanMethods = false)
    /** Built by Spring Cloud OpenFeign exactly like the real clients. */
    @EnableFeignClients(clients = TraceProbeClient.class)
    static class FeignProbe {
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
            assertThat(context.getBean(Propagator.class)).isInstanceOf(FleetOnlyTracePropagator.class);
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
        });
    }

    @Test
    void theTrace_andTheCorrelationId_crossBothAsyncExecutors() {
        runner.withConfiguration(AutoConfigurations.of(TaskExecutionAutoConfiguration.class))
                .withUserConfiguration(AsyncConfig.class)
                .withPropertyValues("spring.task.execution.mode=force")
                .run(context -> {
                    ObservationRegistry registry = context.getBean(ObservationRegistry.class);
                    Tracer tracer = context.getBean(Tracer.class);
                    // Boot's executor (plain @Async) and the ticket-delivery pool.
                    Executor boots = context.getBean("applicationTaskExecutor", TaskExecutor.class);
                    Executor tickets = context.getBean(AsyncConfig.TICKET_DELIVERY_EXECUTOR, ThreadPoolTaskExecutor.class);
                    for (Executor executor : new Executor[] {boots, tickets}) {
                        MDC.put(CorrelationIdFilter.MDC_KEY, "corr-123");
                        Observation.createNotStarted("request", registry).observeChecked(() -> {
                            String submitter = tracer.currentSpan().context().traceId();
                            CompletableFuture<String[]> onWorker = new CompletableFuture<>();
                            executor.execute(() -> onWorker.complete(new String[] {
                                    MDC.get("traceId"), MDC.get(CorrelationIdFilter.MDC_KEY)}));
                            String[] seen = onWorker.get(5, TimeUnit.SECONDS);
                            assertThat(seen[0]).isEqualTo(submitter);
                            assertThat(seen[1]).isEqualTo("corr-123");
                            return null;
                        });
                    }
                });
    }

    @Test
    void feignClients_sendTraceparent() {
        wireMock.stubFor(get(urlEqualTo("/probe")).willReturn(ok("pong")));
        runner.withConfiguration(AutoConfigurations.of(
                        JacksonAutoConfiguration.class,
                        HttpMessageConvertersAutoConfiguration.class,
                        FeignAutoConfiguration.class))
                .withUserConfiguration(FeignProbe.class)
                .withPropertyValues("test.feign-url=http://localhost:" + wireMock.port())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(TraceProbeClient.class).probe()).isEqualTo("pong");
                    wireMock.verify(getRequestedFor(urlEqualTo("/probe"))
                            .withHeader("traceparent", matching(TRACEPARENT)));
                });
    }

    @Test
    void theBuilderBean_sendsTraceparent_toAFleetService() {
        runner.run(context -> {
            RestClient.Builder builder = context.getBean(RestClient.Builder.class);
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            server.expect(requestTo("http://event-service/events/internal/x"))
                    .andExpect(header("traceparent", org.hamcrest.Matchers.matchesPattern(TRACEPARENT)))
                    .andRespond(withSuccess());

            builder.build().get().uri("http://event-service/events/internal/x").retrieve().toBodilessEntity();
            server.verify();
        });
    }

    @Test
    void theSameObservedBuilder_sendsNothing_toAPartner() {
        runner.run(context -> {
            RestClient.Builder builder = context.getBean(RestClient.Builder.class);
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            server.expect(requestTo("https://whatsapp.example.co.zw/send"))
                    .andExpect(headerDoesNotExist("traceparent"))
                    .andExpect(headerDoesNotExist("tracestate"))
                    .andExpect(headerDoesNotExist("baggage"))
                    .andRespond(withSuccess());

            builder.build().get().uri("https://whatsapp.example.co.zw/send").retrieve().toBodilessEntity();
            server.verify();
        });
    }
}
