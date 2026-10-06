package innbucks.paymentservice.client;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryEventPublisherBeansApplicationListener;

import java.util.function.Supplier;

/**
 * Runs a partner call the way production runs it: inside a live trace (a
 * Micrometer observation on a real OpenTelemetry tracer, W3C propagation),
 * so a contract test can pin that the trace still does NOT reach the partner
 * (CLAUDE.md "Tracing and compression"). No Spring context.
 */
final class ActiveTrace {

    static {
        // What every Spring application start does first. Done here too so a
        // contract test that happens to run before any context in this JVM
        // cannot initialise OTel's context storage without Boot's MDC hook.
        OpenTelemetryEventPublisherBeansApplicationListener.addWrapper();
    }

    private static final OpenTelemetrySdk SDK = OpenTelemetrySdk.builder()
            .setTracerProvider(SdkTracerProvider.builder().build())
            .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
            .build();

    private static final OtelTracer TRACER =
            new OtelTracer(SDK.getTracer("contract-test"), new OtelCurrentTraceContext(), event -> { });

    private static final ObservationRegistry REGISTRY = ObservationRegistry.create();

    static {
        REGISTRY.observationConfig().observationHandler(new DefaultTracingObservationHandler(TRACER));
    }

    private ActiveTrace() {
    }

    static <T> T within(Supplier<T> call) {
        return Observation.createNotStarted("inbound-request", REGISTRY).observe(() -> {
            if (TRACER.currentSpan() == null) {
                throw new IllegalStateException("no trace active — the assertion would prove nothing");
            }
            return call.get();
        });
    }
}
