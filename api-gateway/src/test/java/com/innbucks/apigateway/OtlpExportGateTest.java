package com.innbucks.apigateway;

import io.micrometer.tracing.Tracer;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration;
import org.springframework.boot.micrometer.tracing.autoconfigure.MicrometerTracingAutoConfiguration;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingAutoConfiguration;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.otlp.OtlpTracingAutoConfiguration;
import org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Export is OFF unless a collector is named" — and off means no exporter
 * bean at all, so nothing retries against a missing collector and nothing logs
 * an export failure every few seconds (CLAUDE.md "Tracing and compression").
 *
 * <p>The chain this pins, link by link:
 * <ol>
 *   <li>Boot maps {@code OTEL_EXPORTER_OTLP_TRACES_ENDPOINT} onto
 *       {@code management.opentelemetry.tracing.export.otlp.endpoint} and
 *       SKIPS a blank value — so the cell's committed
 *       {@code OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=} sets nothing;</li>
 *   <li>with no endpoint property there is no exporter bean;</li>
 *   <li>with one, there is (the gate really is the endpoint);</li>
 *   <li>an EMPTY endpoint property — what a {@code ${OTEL_...:}} line in
 *       application.yaml would produce — FAILS THE BOOT, which is why no
 *       application.yaml maps it;</li>
 *   <li>this module's real application.yaml leaves the exporter off and the
 *       sampling at 10%.</li>
 * </ol>
 */
class OtlpExportGateTest {

    private static final String ENDPOINT = "management.opentelemetry.tracing.export.otlp.endpoint";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ObservationAutoConfiguration.class,
                    OpenTelemetrySdkAutoConfiguration.class,
                    OpenTelemetryTracingAutoConfiguration.class,
                    OtlpTracingAutoConfiguration.class,
                    MicrometerTracingAutoConfiguration.class));

    @Test
    void aBlankEnvVar_mapsToNoEndpointAtAll() throws Exception {
        StandardEnvironment env = postProcessed(Map.of("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT", ""));

        assertThat(env.containsProperty(ENDPOINT)).isFalse();
    }

    @Test
    void aSetEnvVar_mapsToTheEndpoint() throws Exception {
        StandardEnvironment env = postProcessed(
                Map.of("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT", "http://otel-collector:4318/v1/traces"));

        assertThat(env.getProperty(ENDPOINT)).isEqualTo("http://otel-collector:4318/v1/traces");
    }

    @Test
    void noEndpoint_tracerButNoExporter() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(Tracer.class);
            assertThat(context).doesNotHaveBean(SpanExporter.class);
        });
    }

    @Test
    void anEndpoint_createsTheOtlpExporter() {
        runner.withPropertyValues(ENDPOINT + "=http://otel-collector:4318/v1/traces")
                .run(context -> assertThat(context).hasSingleBean(OtlpHttpSpanExporter.class));
    }

    @Test
    void anEmptyEndpointProperty_failsTheBoot() {
        // An empty value still satisfies the exporter's @ConditionalOnProperty,
        // and the OTLP builder then refuses "". So a yaml line defaulting the
        // endpoint to "" would crash-loop every pod of a cell that left it
        // blank — the reason no application.yaml maps it.
        runner.withPropertyValues(ENDPOINT + "=")
                .run(context -> assertThat(context).getFailure()
                        .rootCause()
                        .hasMessageContaining("Invalid endpoint"));
    }

    @Test
    void thisModulesApplicationYaml_exportsNothing_andSamplesTenPercent() {
        runner.withInitializer(new ConfigDataApplicationContextInitializer())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(Tracer.class);
                    assertThat(context).doesNotHaveBean(SpanExporter.class);
                    assertThat(context.getEnvironment().getProperty("management.tracing.sampling.probability"))
                            .isEqualTo("0.1");
                    assertThat(context.getEnvironment().getProperty("management.tracing.export.enabled"))
                            .isEqualTo("true");
                    assertThat(context.getEnvironment().containsProperty("management.otlp.tracing.endpoint"))
                            .as("the retired localhost:4318 default must not come back")
                            .isFalse();
                });
    }

    /**
     * Runs Boot's own OTEL_* env-var post-processor over a fake environment.
     * Its env-injecting constructor is package-private, hence reflection; if
     * a Boot upgrade renames it, this test fails loudly rather than passing.
     */
    private static StandardEnvironment postProcessed(Map<String, String> envVars) throws Exception {
        String pkg = "org.springframework.boot.opentelemetry.autoconfigure.";
        Class<?> varsType = Class.forName(pkg + "OpenTelemetryEnvironmentVariables");
        Method forMap = varsType.getDeclaredMethod("forMap", DeferredLogFactory.class, Map.class);
        forMap.setAccessible(true);
        DeferredLogFactory logs = destination -> destination.get();
        Object vars = forMap.invoke(null, logs, envVars);

        Class<?> ppType = Class.forName(pkg + "OpenTelemetryEnvironmentVariableEnvironmentPostProcessor");
        Constructor<?> ctor = ppType.getDeclaredConstructor(DeferredLogFactory.class, varsType);
        ctor.setAccessible(true);
        EnvironmentPostProcessor pp = (EnvironmentPostProcessor) ctor.newInstance(logs, vars);

        StandardEnvironment env = new StandardEnvironment();
        pp.postProcessEnvironment(env, new org.springframework.boot.SpringApplication());
        return env;
    }
}
