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
 * Every JVM pod's heap ceiling must leave room, inside the pod's memory limit,
 * for everything else the JVM uses.
 *
 * <p>The heap is only part of a JVM's memory. Metaspace, the code cache, GC
 * structures, symbols, thread stacks and the JVM's own native memory come on
 * top. event-service measured about 336 MiB of that while idle (RSS minus the
 * committed heap, production's flags, 2026-11-12), and it grows under load
 * (more compiled code, more threads). When the heap may grow to
 * {@code MaxRAMPercentage} of the limit and that leaves less than this, a heap
 * that fills up gets the pod OOMKilled by the kernel: no Java
 * {@code OutOfMemoryError}, no log line. That is how production's event-service
 * went down at 70% of 640Mi and of 768Mi, when a burst of startup garbage made
 * G1 grow the heap to its maximum.
 *
 * <p>Read from the manifests, like {@link PostgresConnectionBudgetTest}. A JVM
 * pod is any container running one of our images ({@code ghcr.io/mpofuslim/}),
 * including loyalty-service, marketplace-service and loans-service, whose code
 * lives in other repos. Each must size its heap by {@code MaxRAMPercentage}
 * alone (an {@code -Xmx}, {@code -XX:MaxHeapSize} or {@code -XX:MaxRAM} would
 * size it behind this check) and set a memory limit.
 */
class JvmMemoryBudgetTest {

    /**
     * What the limit must hold beyond the heap ceiling. event-service measured
     * 336 MiB outside the heap while idle, and 365 MiB with the heap grown to its
     * maximum (the broken build's startup at 1Gi and 55%: 929 MiB peak RSS), plus
     * room for more threads and compiled code under load.
     */
    static final long NON_HEAP_MIB = 448;

    private static final Path K8S = Path.of(System.getProperty("user.dir")).getParent().resolve("deploy/k8s");
    private static final String OUR_IMAGES = "ghcr.io/mpofuslim/";
    private static final Pattern MAX_RAM_PERCENTAGE = Pattern.compile("-XX:MaxRAMPercentage=(\\d+(?:\\.\\d+)?)");
    private static final Pattern HEAP_SIZED_ELSEWHERE = Pattern.compile("-Xmx|-XX:MaxHeapSize|-XX:MaxRAM=");

    record JvmPod(String deployment, long limitMib, double heapPercent) {
        long heapMib() {
            return (long) Math.ceil(limitMib * heapPercent / 100.0);
        }

        long leftMib() {
            return limitMib - heapMib();
        }
    }

    @Test
    void everyJvmPod_leavesRoomOutsideTheHeap() throws IOException {
        List<JvmPod> pods = jvmPods();
        assertThat(pods).extracting(JvmPod::deployment)
                .as("the scan must find every JVM Deployment")
                .contains("user-service", "event-service", "seat-service", "booking-service", "payment-service",
                        "loyalty-service", "marketplace-service", "api-gateway", "loans-service");

        List<String> tooTight = new ArrayList<>();
        for (JvmPod p : pods) {
            if (p.leftMib() < NON_HEAP_MIB) {
                tooTight.add(String.format("%s: limit %d MiB, heap up to %d MiB (%.1f%%), leaves %d MiB",
                        p.deployment(), p.limitMib(), p.heapMib(), p.heapPercent(), p.leftMib()));
            }
        }
        assertThat(tooTight)
                .as("JVM pods whose heap ceiling leaves less than %d MiB of the limit for the rest of the JVM; "
                        + "raise the limit or lower MaxRAMPercentage", NON_HEAP_MIB)
                .isEmpty();
    }

    @Test
    void theRuleCatchesTheSettingThatTookEventServiceDown() {
        assertThat(new JvmPod("event-service", 640, 70.0).leftMib()).isLessThan(NON_HEAP_MIB);
        assertThat(new JvmPod("event-service", 768, 70.0).leftMib()).isLessThan(NON_HEAP_MIB);
        assertThat(new JvmPod("event-service", 1024, 55.0).leftMib()).isGreaterThanOrEqualTo(NON_HEAP_MIB);
    }

    private static List<JvmPod> jvmPods() throws IOException {
        List<Path> manifests;
        try (Stream<Path> s = Files.walk(K8S)) {
            manifests = s.filter(p -> p.toString().endsWith(".yaml")).sorted().toList();
        }
        List<JvmPod> pods = new ArrayList<>();
        for (Path file : manifests) {
            for (Map<String, Object> doc : documents(file)) {
                if (!"Deployment".equals(doc.get("kind"))) {
                    continue;
                }
                for (Map<String, Object> c : containers(doc)) {
                    if (!String.valueOf(c.get("image")).startsWith(OUR_IMAGES)) {
                        continue;
                    }
                    String where = name(doc) + " (" + K8S.relativize(file) + ")";
                    String options = javaToolOptions(c);
                    assertThat(options).as(where + " must set JAVA_TOOL_OPTIONS with -XX:MaxRAMPercentage").isNotNull();
                    assertThat(HEAP_SIZED_ELSEWHERE.matcher(options).find())
                            .as(where + " sizes its heap outside MaxRAMPercentage: " + options)
                            .isFalse();
                    Matcher m = MAX_RAM_PERCENTAGE.matcher(options);
                    assertThat(m.find()).as(where + " must set -XX:MaxRAMPercentage: " + options).isTrue();
                    Object limit = map(map(c.get("resources")).get("limits")).get("memory");
                    assertThat(limit).as(where + " must set a memory limit").isNotNull();
                    pods.add(new JvmPod(name(doc), PostgresConnectionBudgetTest.mebibytes(String.valueOf(limit)),
                            Double.parseDouble(m.group(1))));
                }
            }
        }
        return pods;
    }

    private static String javaToolOptions(Map<String, Object> container) {
        for (Object e : list(container.get("env"))) {
            Map<String, Object> entry = map(e);
            if ("JAVA_TOOL_OPTIONS".equals(entry.get("name")) && entry.get("value") != null) {
                return String.valueOf(entry.get("value"));
            }
        }
        return null;
    }

    // --- tiny YAML helpers, as in PostgresConnectionBudgetTest ---------------

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

    private static String name(Map<String, Object> doc) {
        return String.valueOf(map(doc.get("metadata")).get("name"));
    }

    private static List<Map<String, Object>> containers(Map<String, Object> doc) {
        Map<String, Object> podSpec = map(map(map(doc.get("spec")).get("template")).get("spec"));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object c : list(podSpec.get("containers"))) {
            out.add(map(c));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static List<?> list(Object o) {
        return o instanceof List<?> l ? l : List.of();
    }
}
