package com.innbucks.apigateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.simple.SimpleDiscoveryClient;
import org.springframework.cloud.client.discovery.simple.SimpleDiscoveryProperties;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the fleet's service-discovery map: the {@code simple.instances} block
 * every service carries in its application.yaml now that Eureka is retired.
 *
 * <p>The map is the ONLY thing turning a service name into an address, so each
 * way it can go wrong is a silent outage rather than a build error: a sibling
 * missing from one service's copy is a "No servers available" 503 on the calls
 * that service makes, and a port that disagrees with the k8s Service is a
 * connection refused. Neither shows up until the call is made in the cell.
 * This test turns all of them into a red build:
 *
 * <ul>
 *   <li>every service carries the SAME map (default and {@code local});</li>
 *   <li>each entry is {@code http://<name>:<port>} and the port equals that
 *       name's Service port in {@code deploy/k8s};</li>
 *   <li>every {@code lb://} gateway route and every {@code http://<x>-service}
 *       address in main code or config has an entry;</li>
 *   <li>no service configures Eureka any more;</li>
 *   <li>the YAML binds into Spring Cloud's real {@link SimpleDiscoveryClient},
 *       which resolves each name to the expected host and port.</li>
 * </ul>
 *
 * <p>Pure JUnit reading files from the repository, so it needs neither Docker
 * nor a Spring context. Surefire runs it with the module directory as the
 * working directory, which puts the repository root one level up.
 */
class FleetServiceMapTest {

    private static final List<String> SERVICES = List.of(
            "api-gateway", "user-service", "event-service", "seat-service",
            "booking-service", "payment-service");

    private static final String PREFIX = "spring.cloud.discovery.client.simple.instances";
    private static final Pattern ENTRY = Pattern.compile(
            Pattern.quote(PREFIX) + "\\.([a-z-]+)\\[0]\\.uri");
    private static final Pattern SIBLING_ADDRESS = Pattern.compile("http://([a-z]+-service)\\b");
    private static final Pattern LB_ROUTE = Pattern.compile("lb://([a-z-]+)");

    private static final Path ROOT = Path.of(System.getProperty("user.dir")).getParent();

    @Test
    void everyServiceCarriesTheSameMap() throws IOException {
        Map<String, String> reference = map("api-gateway", null);
        Map<String, String> referenceLocal = map("api-gateway", "local");
        assertThat(reference).as("api-gateway default map").isNotEmpty();
        for (String service : SERVICES) {
            assertThat(map(service, null)).as(service + " default map").isEqualTo(reference);
            assertThat(map(service, "local")).as(service + " local map").isEqualTo(referenceLocal);
        }
    }

    @Test
    void everyEntryIsItsOwnKubernetesServiceOnThatServicesPort() throws IOException {
        Map<String, Integer> k8sPorts = kubernetesServicePorts();
        for (Map.Entry<String, String> e : map("api-gateway", null).entrySet()) {
            URI uri = URI.create(e.getValue());
            assertThat(uri.getScheme()).as(e.getKey()).isEqualTo("http");
            assertThat(uri.getHost()).as(e.getKey() + " host must be its k8s Service name")
                    .isEqualTo(e.getKey());
            assertThat(k8sPorts).as("deploy/k8s must define a Service named " + e.getKey())
                    .containsKey(e.getKey());
            assertThat(uri.getPort()).as(e.getKey() + " port vs its k8s Service")
                    .isEqualTo(k8sPorts.get(e.getKey()));
        }
    }

    @Test
    void theLocalProfileIsTheSameNamesAndPortsOnLocalhost() throws IOException {
        Map<String, String> cluster = map("api-gateway", null);
        Map<String, String> local = map("api-gateway", "local");
        assertThat(local.keySet()).isEqualTo(cluster.keySet());
        for (String name : cluster.keySet()) {
            URI clusterUri = URI.create(cluster.get(name));
            URI localUri = URI.create(local.get(name));
            assertThat(localUri.getHost()).as(name).isEqualTo("localhost");
            assertThat(localUri.getPort()).as(name).isEqualTo(clusterUri.getPort());
        }
    }

    @Test
    void everyGatewayRouteAndEverySiblingAddressHasAnEntry() throws IOException {
        Set<String> mapped = map("api-gateway", null).keySet();

        String gatewayYaml = Files.readString(applicationYaml("api-gateway"));
        Set<String> routed = matches(LB_ROUTE, gatewayYaml);
        assertThat(routed).as("the gateway routes somewhere").isNotEmpty();
        assertThat(mapped).as("lb:// targets with no discovery entry").containsAll(routed);

        Set<String> called = new TreeSet<>();
        for (String service : SERVICES) {
            try (Stream<Path> files = Files.walk(ROOT.resolve(service).resolve("src/main"))) {
                for (Path f : files.filter(p -> p.toString().endsWith(".java")
                        || p.toString().endsWith(".yaml") || p.toString().endsWith(".yml")).toList()) {
                    called.addAll(matches(SIBLING_ADDRESS, Files.readString(f)));
                }
            }
        }
        assertThat(called).as("services address siblings by name").isNotEmpty();
        assertThat(mapped).as("http://<name> addresses with no discovery entry").containsAll(called);
    }

    @Test
    void noServiceConfiguresEurekaAnyMore() throws IOException {
        for (String service : SERVICES) {
            for (PropertySource<?> doc : documents(service)) {
                if (doc instanceof MapPropertySource m) {
                    assertThat(m.getSource().keySet())
                            .as(service + " still configures Eureka")
                            .noneMatch(k -> k.startsWith("eureka."));
                }
            }
        }
    }

    @Test
    void noDeployManifestOrCellEnvCarriesEurekaConfig() throws IOException {
        // discovery-server is deleted. A leftover EUREKA_* configMapKeyRef /
        // secretKeyRef is worse than dead config: once the key is gone from
        // the cell ConfigMap/Secret, the pod is CreateContainerConfigError.
        List<Path> files;
        try (Stream<Path> k8s = Files.list(ROOT.resolve("deploy/k8s"));
             Stream<Path> cells = Files.list(ROOT.resolve("deploy/cells"))) {
            files = Stream.concat(k8s, cells)
                    .filter(p -> p.toString().endsWith(".yaml") || p.toString().endsWith(".env"))
                    .toList();
        }
        assertThat(files).as("deploy files scanned").isNotEmpty();
        for (Path f : files) {
            assertThat(Files.readString(f)).as(ROOT.relativize(f) + " still carries Eureka config")
                    .doesNotContain("EUREKA_")
                    .doesNotContain("discovery-server");
        }
    }

    @Test
    void theMapBindsIntoSpringsDiscoveryClientAndResolvesEachName() throws IOException {
        MapPropertySource doc = defaultDocument("api-gateway");
        SimpleDiscoveryProperties properties = new Binder(ConfigurationPropertySources.from(doc))
                .bind("spring.cloud.discovery.client.simple", Bindable.of(SimpleDiscoveryProperties.class))
                .orElseThrow(() -> new AssertionError("simple discovery properties did not bind"));
        properties.afterPropertiesSet();
        SimpleDiscoveryClient client = new SimpleDiscoveryClient(properties);

        Map<String, Integer> k8sPorts = kubernetesServicePorts();
        for (String name : map("api-gateway", null).keySet()) {
            List<ServiceInstance> instances = client.getInstances(name);
            assertThat(instances).as(name).hasSize(1);
            ServiceInstance instance = instances.get(0);
            assertThat(instance.getServiceId()).as(name).isEqualTo(name);
            assertThat(instance.getHost()).as(name).isEqualTo(name);
            assertThat(instance.getPort()).as(name).isEqualTo(k8sPorts.get(name));
        }
        assertThat(client.getServices()).containsExactlyInAnyOrderElementsOf(
                map("api-gateway", null).keySet());
    }

    // ---- helpers -------------------------------------------------------------

    private static Path applicationYaml(String service) {
        return ROOT.resolve(service).resolve("src/main/resources/application.yaml");
    }

    private static List<PropertySource<?>> documents(String service) throws IOException {
        return new YamlPropertySourceLoader().load(service,
                new FileSystemResource(applicationYaml(service)));
    }

    /** The one document that carries the map and no profile activation. */
    private static MapPropertySource defaultDocument(String service) throws IOException {
        return mapDocument(service, null);
    }

    private static MapPropertySource mapDocument(String service, String profile) throws IOException {
        List<MapPropertySource> found = new ArrayList<>();
        for (PropertySource<?> doc : documents(service)) {
            if (!(doc instanceof MapPropertySource m)) continue;
            Object activation = m.getProperty("spring.config.activate.on-profile");
            boolean hasMap = m.getSource().keySet().stream().anyMatch(k -> k.startsWith(PREFIX + "."));
            boolean profileMatches = profile == null
                    ? activation == null
                    : profile.equals(String.valueOf(activation));
            if (hasMap && profileMatches) found.add(m);
        }
        assertThat(found).as(service + " documents carrying the "
                + (profile == null ? "default" : profile) + " discovery map").hasSize(1);
        return found.get(0);
    }

    private static Map<String, String> map(String service, String profile) throws IOException {
        Map<String, String> out = new TreeMap<>();
        for (Map.Entry<String, Object> e : mapDocument(service, profile).getSource().entrySet()) {
            Matcher m = ENTRY.matcher(e.getKey());
            if (m.matches()) out.put(m.group(1), String.valueOf(e.getValue()));
        }
        return out;
    }

    /** name -> port of every {@code kind: Service} in deploy/k8s/*.yaml. */
    @SuppressWarnings("unchecked")
    private static Map<String, Integer> kubernetesServicePorts() throws IOException {
        Map<String, Integer> ports = new TreeMap<>();
        try (Stream<Path> files = Files.list(ROOT.resolve("deploy/k8s"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".yaml")).toList()) {
                try (Reader r = Files.newBufferedReader(f)) {
                    for (Object doc : new Yaml().loadAll(r)) {
                        if (!(doc instanceof Map<?, ?> d) || !"Service".equals(d.get("kind"))) continue;
                        String name = String.valueOf(((Map<String, Object>) d.get("metadata")).get("name"));
                        List<Map<String, Object>> svcPorts =
                                (List<Map<String, Object>>) ((Map<String, Object>) d.get("spec")).get("ports");
                        ports.put(name, ((Number) svcPorts.get(0).get("port")).intValue());
                    }
                }
            }
        }
        return ports;
    }

    private static Set<String> matches(Pattern pattern, String text) {
        Set<String> out = new TreeSet<>();
        Matcher m = pattern.matcher(text);
        while (m.find()) out.add(m.group(1));
        return out;
    }
}
