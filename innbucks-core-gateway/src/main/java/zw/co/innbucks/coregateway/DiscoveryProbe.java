package zw.co.innbucks.coregateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Connectivity spike (milestone 1): proves this adapter can reach the InnBucks
 * Eureka cluster and SEE {@code veengu-integration} (plus the other core
 * services) registered. No {@code core} jar and no transaction yet — the only
 * question here is "is the discovery bridge wired and reachable?".
 *
 * <p>On startup it logs what the registry returns; the two endpoints let you
 * poke it from a browser/curl on the host you run it from.
 */
@RestController
@Component
class DiscoveryProbe {

    private static final Logger log = LoggerFactory.getLogger(DiscoveryProbe.class);
    private static final String TARGET = "veengu-integration";

    private final DiscoveryClient discoveryClient;

    DiscoveryProbe(DiscoveryClient discoveryClient) {
        this.discoveryClient = discoveryClient;
    }

    @EventListener(ApplicationReadyEvent.class)
    void probeOnStartup() {
        List<String> services = discoveryClient.getServices();
        log.info("[discovery] {} service(s) visible in the InnBucks registry: {}", services.size(), services);
        List<ServiceInstance> veengu = discoveryClient.getInstances(TARGET);
        if (veengu.isEmpty()) {
            log.warn("[discovery] '{}' is NOT visible yet — check network reachability to the "
                    + "Eureka cluster and that veengu-integration is actually registered.", TARGET);
        } else {
            veengu.forEach(i -> log.info("[discovery] {} -> {}:{} (uri={})",
                    TARGET, i.getHost(), i.getPort(), i.getUri()));
        }
    }

    /** All service names currently registered in the InnBucks registry. */
    @GetMapping("/discovery/services")
    List<String> services() {
        return discoveryClient.getServices();
    }

    /** Live instances of a given service (e.g. GET /discovery/veengu-integration). */
    @GetMapping("/discovery/{service}")
    List<Map<String, Object>> instances(@PathVariable String service) {
        return discoveryClient.getInstances(service).stream()
                .map(i -> Map.<String, Object>of(
                        "host", i.getHost(),
                        "port", i.getPort(),
                        "uri", i.getUri().toString(),
                        "secure", i.isSecure()))
                .toList();
    }
}
