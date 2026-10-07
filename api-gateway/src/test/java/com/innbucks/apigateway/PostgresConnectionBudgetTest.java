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
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the cell's Postgres connection budget: every Hikari pool in the cell,
 * times its replicas, plus one rollout's extra pod, plus a reserve for people
 * and backups, must fit the {@code max_connections} the postgres StatefulSet
 * starts with.
 *
 * <p>Going over is silent until the worst moment: Postgres answers the
 * connection that does not fit with {@code FATAL: sorry, too many clients
 * already}, which reaches whichever pool happened to grow last — typically the
 * new pod of a rollout, or booking/seat in a sale — as a failed health check or
 * a 5xx. Every service connects as the cell superuser, so even Postgres's own
 * {@code superuser_reserved_connections} are open to the pools and an operator
 * cannot get a {@code psql} in to look. Before this test the cell's pools
 * summed to exactly {@code max_connections} with nothing left for a rollout or
 * an operator.
 *
 * <p>The manifests are the source of truth, so this reads them:
 * <ul>
 *   <li>{@code max_connections} from the postgres StatefulSet's args;</li>
 *   <li>every Deployment that talks to the cell postgres (a {@code DB_URL} of
 *       {@code jdbc:postgresql://postgres:…}) — including loyalty-service,
 *       marketplace-service and loans-service, whose Deployments live here
 *       although their code lives in other repos;</li>
 *   <li>its {@code replicas}, its rollout surge (Recreate = none; RollingUpdate
 *       = {@code maxSurge}, default 25% rounded up, i.e. one extra pod at one
 *       replica) and its {@code DB_POOL_MAX}/{@code DB_POOL_MIN}, which every
 *       such Deployment must now set explicitly — a packaged default in another
 *       repo can then never move this sum without a change here.</li>
 * </ul>
 *
 * <p>Pure JUnit reading files from the repository, like {@link FleetServiceMapTest}.
 */
class PostgresConnectionBudgetTest {

    /**
     * Connections kept free of every pool: the nightly {@code pg_dumpall}
     * ({@code scripts/backup-postgres.sh}, one at a time plus one for globals),
     * operators' {@code psql} sessions during an incident, marketplace-service's
     * Flyway, which opens its own unpooled connections at start-up, and slack.
     */
    static final int RESERVE = 10;

    /**
     * The services whose code is in another repo, with the pool properties
     * their packaged {@code application.y*ml} binds — read from those repos,
     * because CI here cannot. The test requires their Deployments to set both
     * variables, so these defaults never count; they are here so a reader
     * knows what an unset value would have meant, and that the variables
     * reach the pool at all.
     */
    static final Map<String, String> EXTERNAL_POOL_BINDINGS = Map.of(
            // MpofuSlim/InnRewards src/main/resources/application.yaml
            "loyalty-service", "maximum-pool-size: ${DB_POOL_MAX:20}, minimum-idle: ${DB_POOL_MIN:10}",
            // MpofuSlim/market-place src/main/resources/application.yaml
            "marketplace-service", "maximum-pool-size: ${DB_POOL_MAX:20}, minimum-idle: ${DB_POOL_MIN:10}",
            // MpofuSlim/innbucks-loans loans-api/src/main/resources/application.yml
            "loans-service", "maximum-pool-size: ${DB_POOL_MAX:19}, minimum-idle: ${DB_POOL_MIN:7}");

    /** The services in THIS repo that use Postgres; their binding is checked in place. */
    static final List<String> LOCAL_DB_SERVICES = List.of(
            "user-service", "event-service", "seat-service", "booking-service", "payment-service");

    private static final Path ROOT = Path.of(System.getProperty("user.dir")).getParent();
    private static final Path K8S = ROOT.resolve("deploy/k8s");
    private static final Pattern MAX_CONNECTIONS = Pattern.compile("^max_connections=(\\d+)$");

    record Pool(String deployment, int replicas, int surge, int max, int min) {
        int steady() {
            return max * replicas;
        }

        int surgeConnections() {
            return max * surge;
        }
    }

    @Test
    void thePoolsFitMaxConnectionsWithARolloutAndTheReserve() throws IOException {
        int maxConnections = maxConnections();
        List<Pool> pools = pools();

        int steady = pools.stream().mapToInt(Pool::steady).sum();
        int surge = pools.stream().mapToInt(Pool::surgeConnections).max().orElse(0);
        int worstCase = steady + surge + RESERVE;

        assertThat(worstCase)
                .as("Postgres connection budget: sum(pool x replicas) (%d) + the largest rollout surge (%d)"
                                + " + reserve (%d) = %d must be <= max_connections (%d).%n%s%n"
                                + "Lower a DB_POOL_MAX (cheap: no Postgres restart) before raising"
                                + " max_connections (a Postgres restart, ~5-10 MB per connection).",
                        steady, surge, RESERVE, worstCase, maxConnections, table(pools))
                .isLessThanOrEqualTo(maxConnections);
    }

    @Test
    void everyPostgresDeploymentSetsItsPoolExplicitly() throws IOException {
        List<Pool> pools = pools();
        assertThat(pools).extracting(Pool::deployment)
                .as("the cell's Postgres clients")
                .containsAll(LOCAL_DB_SERVICES)
                .containsAll(EXTERNAL_POOL_BINDINGS.keySet());
        for (Pool p : pools) {
            assertThat(p.max()).as(p.deployment() + " DB_POOL_MAX").isPositive();
            assertThat(p.min()).as(p.deployment() + " DB_POOL_MIN").isBetween(0, p.max());
        }
    }

    @Test
    void theVariablesReachTheLocalPools() throws IOException {
        for (String service : LOCAL_DB_SERVICES) {
            String yaml = Files.readString(ROOT.resolve(service).resolve("src/main/resources/application.yaml"));
            assertThat(yaml).as(service + " must bind its Hikari pool to DB_POOL_MAX / DB_POOL_MIN")
                    .contains("maximum-pool-size: ${DB_POOL_MAX:")
                    .contains("minimum-idle: ${DB_POOL_MIN:");
        }
    }

    /**
     * Spring's relaxed binding maps {@code SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE}
     * straight onto the property, ahead of the yaml's {@code ${DB_POOL_MAX}} — so
     * one in a cell env file (every pod's envFrom) or a Deployment would size
     * pools this test cannot see.
     */
    @Test
    void nothingSizesAPoolBehindTheBudgetsBack() throws IOException {
        Pattern bypass = Pattern.compile("(?m)^\\s*(?:-\\s*\\{\\s*name:\\s*)?SPRING_DATASOURCE_HIKARI_\\w+");
        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.walk(ROOT.resolve("deploy"))) {
            s.filter(p -> p.toString().endsWith(".yaml") || p.toString().endsWith(".env")).forEach(files::add);
        }
        assertThat(files).isNotEmpty();
        for (Path f : files) {
            assertThat(bypass.matcher(Files.readString(f)).find())
                    .as(ROOT.relativize(f) + " sets a SPRING_DATASOURCE_HIKARI_* variable").isFalse();
        }
    }

    // ---------------------------------------------------------------------

    private static int maxConnections() throws IOException {
        for (Map<String, Object> doc : documents(K8S.resolve("01-infra.yaml"))) {
            if (!"StatefulSet".equals(doc.get("kind")) || !"postgres".equals(name(doc))) {
                continue;
            }
            for (Map<String, Object> c : containers(doc)) {
                for (Object arg : list(c.get("args"))) {
                    Matcher m = MAX_CONNECTIONS.matcher(String.valueOf(arg));
                    if (m.matches()) {
                        return Integer.parseInt(m.group(1));
                    }
                }
            }
        }
        throw new AssertionError("the postgres StatefulSet in deploy/k8s/01-infra.yaml sets no -c max_connections=N");
    }

    private static List<Pool> pools() throws IOException {
        List<Pool> pools = new ArrayList<>();
        List<Path> manifests;
        try (Stream<Path> s = Files.walk(K8S)) {
            manifests = s.filter(p -> p.toString().endsWith(".yaml")).sorted().toList();
        }
        for (Path file : manifests) {
            for (Map<String, Object> doc : documents(file)) {
                if (!"Deployment".equals(doc.get("kind"))) {
                    continue;
                }
                for (Map<String, Object> c : containers(doc)) {
                    Map<String, String> env = env(c);
                    String url = env.get("DB_URL");
                    if (url == null || !url.startsWith("jdbc:postgresql://postgres:")) {
                        continue;
                    }
                    String deployment = name(doc);
                    assertThat(env).as(deployment + " (" + K8S.relativize(file) + ") must set DB_POOL_MAX and"
                                    + " DB_POOL_MIN explicitly — the connection budget counts the manifest")
                            .containsKeys("DB_POOL_MAX", "DB_POOL_MIN");
                    Map<String, Object> spec = map(doc.get("spec"));
                    int replicas = spec.get("replicas") == null ? 1 : ((Number) spec.get("replicas")).intValue();
                    pools.add(new Pool(deployment, replicas, surge(spec, replicas),
                            Integer.parseInt(env.get("DB_POOL_MAX")), Integer.parseInt(env.get("DB_POOL_MIN"))));
                }
            }
        }
        return pools;
    }

    /** Extra pods a rollout runs beside the old ones: Kubernetes' maxSurge, rounded up. */
    static int surge(Map<String, Object> spec, int replicas) {
        Map<String, Object> strategy = map(spec.get("strategy"));
        if ("Recreate".equals(strategy.get("type"))) {
            return 0;
        }
        Object maxSurge = map(strategy.get("rollingUpdate")).getOrDefault("maxSurge", "25%");
        String text = String.valueOf(maxSurge);
        if (text.endsWith("%")) {
            int percent = Integer.parseInt(text.substring(0, text.length() - 1));
            return (int) Math.ceil(replicas * percent / 100.0);
        }
        return Integer.parseInt(text);
    }

    private static String table(List<Pool> pools) {
        StringBuilder sb = new StringBuilder("deployment            replicas  surge  DB_POOL_MAX  steady\n");
        for (Pool p : pools) {
            sb.append(String.format("%-22s%8d%7d%13d%8d%n", p.deployment(), p.replicas(), p.surge(), p.max(), p.steady()));
        }
        return sb.toString();
    }

    // --- tiny YAML helpers --------------------------------------------------

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

    /** Literal {@code value:} entries only; a {@code valueFrom} is not a number we can budget. */
    private static Map<String, String> env(Map<String, Object> container) {
        Map<String, String> env = new TreeMap<>();
        for (Object e : list(container.get("env"))) {
            Map<String, Object> entry = map(e);
            if (entry.get("value") != null) {
                env.put(String.valueOf(entry.get("name")), String.valueOf(entry.get("value")));
            }
        }
        return env;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static List<?> list(Object o) {
        return o instanceof List<?> l ? l : List.of();
    }
}
