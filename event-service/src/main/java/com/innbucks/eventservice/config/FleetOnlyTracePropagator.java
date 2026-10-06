package com.innbucks.eventservice.config;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.http.HttpRequest;

import java.util.List;

/**
 * Trace context leaves this service only toward the FLEET — never toward a
 * partner (InnBucks, ZimSwitch, EcoCash, the WhatsApp gateway, SES).
 *
 * <p>Wraps the auto-configured {@link Propagator}, so the rule holds for every
 * instrumented client however it was built: a partner client moved onto the
 * observed {@code RestClient.Builder} by a later refactor still sends no
 * {@code traceparent}/{@code tracestate}/{@code baggage}. Two reasons it must
 * not: an internal trace id is nothing a third party should hold, and partner
 * edges filter on headers (EcoCash's WAF allow-lists even the User-Agent) —
 * an unexpected header is a refused payment, not a log line.
 *
 * <p><b>The rule is the host's SHAPE.</b> A fleet peer is addressed by its
 * single-label Kubernetes Service name ({@code http://booking-service},
 * resolved through the discovery map — or {@code localhost} under the
 * {@code local} profile). A partner is reachable from the internet, so it
 * always has a dotted name; an IP literal has dots or colons. Anything that is
 * not an HTTP request we can read a host from is refused too (fail closed).
 * Extraction (inbound) is untouched.
 */
public final class FleetOnlyTracePropagator implements Propagator {

    private final Propagator delegate;

    public FleetOnlyTracePropagator(Propagator delegate) {
        this.delegate = delegate;
    }

    @Override
    public List<String> fields() {
        return delegate.fields();
    }

    @Override
    public <C> void inject(TraceContext context, C carrier, Setter<C> setter) {
        if (isFleetHost(hostOf(carrier))) {
            delegate.inject(context, carrier, setter);
        }
    }

    @Override
    public <C> Span.Builder extract(C carrier, Getter<C> getter) {
        return delegate.extract(carrier, getter);
    }

    static String hostOf(Object carrier) {
        if (carrier instanceof HttpRequest request) {
            return request.getURI().getHost();
        }
        return null;
    }

    /** A single DNS label: no dot (FQDN, IPv4) and no colon (IPv6). */
    static boolean isFleetHost(String host) {
        return host != null && !host.isBlank() && host.indexOf('.') < 0 && host.indexOf(':') < 0;
    }
}
