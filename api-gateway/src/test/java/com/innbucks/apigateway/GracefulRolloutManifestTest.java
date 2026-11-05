package com.innbucks.apigateway;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the drain that keeps a rollout from refusing requests.
 *
 * <p>When a pod is replaced, Kubernetes sends it SIGTERM and removes it from
 * the Service's endpoints at the same moment, and kube-proxy takes a moment to
 * catch up. Spring's graceful shutdown stops accepting connections at once, so
 * for that moment the Service still routes to a pod that refuses: measured on
 * staging (2026-11-05), the gateway answered a 500 ("Connection refused:
 * booking-service:8084") to a request sent as booking-service's rollout
 * finished. Every {@code set image} and restart of every service had that
 * window, a payment confirm or an EcoCash notify included.
 *
 * <p>The fix is in the manifests: a {@code preStop} sleep keeps the pod
 * serving until it is out of the Service, and the pod's grace period covers
 * that sleep plus Spring's own shutdown phase, or the kubelet kills the JVM
 * in the middle of finishing its in-flight requests.
 *
 * <p>Every service Deployment applied by the top-level {@code deploy/k8s}
 * (one with an {@code /actuator/health/readiness} probe) is checked. The
 * monitoring stack and loans ({@code strategy: Recreate}: its old pod is gone
 * before the new one starts, so there is no window to drain) are not.
 */
class GracefulRolloutManifestTest {

    private static final Path ROOT = Path.of(System.getProperty("user.dir")).getParent();
    private static final Path K8S = ROOT.resolve("deploy/k8s");

    /** Long enough for kube-proxy and the gateway's connection pool to stop using the pod. */
    static final int MIN_DRAIN_SECONDS = 5;

    /** {@code spring.lifecycle.timeout-per-shutdown-phase} in every service. */
    static final int SPRING_SHUTDOWN_SECONDS = 30;

    /** Left for the JVM to exit after Spring's phase ends. */
    static final int EXIT_MARGIN_SECONDS = 5;

    /** The services whose application.yaml is in this repo. */
    static final List<String> LOCAL_SERVICES = List.of(
            "api-gateway", "user-service", "event-service", "seat-service", "booking-service", "payment-service");

    private static final Pattern SHUTDOWN_PHASE =
            Pattern.compile("timeout-per-shutdown-phase:\\s*(\\d+)s");

    record Drain(String deployment, Integer sleepSeconds, Integer graceSeconds) { }

    @Test
    void everyServiceDeploymentDrainsBeforeItStops() throws IOException {
        List<Drain> drains = drains();

        assertThat(drains)
                .as("the service Deployments in deploy/k8s")
                .extracting(Drain::deployment)
                .contains("api-gateway", "user-service", "event-service", "seat-service",
                        "booking-service", "payment-service", "loyalty-service", "marketplace-service");

        for (Drain d : drains) {
            assertThat(d.sleepSeconds())
                    .as("%s needs lifecycle.preStop.exec [\"sleep\", \"N\"] with N >= %d, so a rollout"
                            + " never routes a request to a pod that has stopped listening",
                            d.deployment(), MIN_DRAIN_SECONDS)
                    .isNotNull()
                    .isGreaterThanOrEqualTo(MIN_DRAIN_SECONDS);
            int needed = d.sleepSeconds() + SPRING_SHUTDOWN_SECONDS + EXIT_MARGIN_SECONDS;
            assertThat(d.graceSeconds())
                    .as("%s: terminationGracePeriodSeconds must cover the preStop sleep (%ds) + Spring's"
                            + " shutdown phase (%ds) + %ds, or the kubelet kills in-flight requests",
                            d.deployment(), d.sleepSeconds(), SPRING_SHUTDOWN_SECONDS, EXIT_MARGIN_SECONDS)
                    .isNotNull()
                    .isGreaterThanOrEqualTo(needed);
        }
    }

    @Test
    void springsShutdownPhaseIsTheOneTheGracePeriodWasSizedFor() throws IOException {
        for (String service : LOCAL_SERVICES) {
            String yaml = Files.readString(ROOT.resolve(service).resolve("src/main/resources/application.yaml"));
            Matcher m = SHUTDOWN_PHASE.matcher(yaml);
            assertThat(m.find())
                    .as("%s sets spring.lifecycle.timeout-per-shutdown-phase", service)
                    .isTrue();
            assertThat(Integer.parseInt(m.group(1)))
                    .as("%s's shutdown phase; raise terminationGracePeriodSeconds with it", service)
                    .isLessThanOrEqualTo(SPRING_SHUTDOWN_SECONDS);
        }
    }

    private static List<Drain> drains() throws IOException {
        List<Drain> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(K8S)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".yaml")).sorted().toList()) {
                for (Map<String, Object> doc : documents(f)) {
                    if (!"Deployment".equals(doc.get("kind"))) {
                        continue;
                    }
                    Map<String, Object> podSpec = map(map(map(doc.get("spec")).get("template")).get("spec"));
                    for (Object c : list(podSpec.get("containers"))) {
                        Map<String, Object> container = map(c);
                        if (!isSpringService(container)) {
                            continue;
                        }
                        out.add(new Drain(
                                String.valueOf(map(doc.get("metadata")).get("name")),
                                preStopSleep(container),
                                (Integer) podSpec.get("terminationGracePeriodSeconds")));
                    }
                }
            }
        }
        return out;
    }

    private static boolean isSpringService(Map<String, Object> container) {
        Object path = map(map(container.get("readinessProbe")).get("httpGet")).get("path");
        return "/actuator/health/readiness".equals(path);
    }

    private static Integer preStopSleep(Map<String, Object> container) {
        List<Object> command = list(map(map(map(container.get("lifecycle")).get("preStop")).get("exec")).get("command"));
        if (command.size() == 2 && "sleep".equals(command.get(0))) {
            try {
                return Integer.parseInt(String.valueOf(command.get(1)));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static List<Map<String, Object>> documents(Path file) throws IOException {
        List<Map<String, Object>> docs = new ArrayList<>();
        try (Reader r = Files.newBufferedReader(file)) {
            for (Object o : new Yaml().loadAll(r)) {
                if (o instanceof Map<?, ?>) {
                    docs.add(map(o));
                }
            }
        }
        return docs;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object o) {
        return o instanceof List<?> l ? (List<Object>) l : List.of();
    }
}
