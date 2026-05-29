package zw.co.innbucks.coregateway;

import feign.FeignException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import zw.co.innbucks.core.dto.kineto.ParticipantConfigDto;
import zw.co.innbucks.core.rest.client.VeenguClient;

import java.util.HashMap;
import java.util.Map;

/**
 * Milestone-2 probe: proves the adapter can actually CALL veengu through the core
 * {@link VeenguClient} (Eureka discovery + Feign + the X-Source-Component header),
 * not just see it in the registry. Read-only — it hits veengu's GET participant-config
 * endpoint, so it never creates a transaction.
 */
@RestController
class VeenguProbe {

    private static final Logger log = LoggerFactory.getLogger(VeenguProbe.class);

    private final VeenguClient veenguClient;

    VeenguProbe(VeenguClient veenguClient) {
        this.veenguClient = veenguClient;
    }

    /**
     * GET /veengu/participant/{id}/config — read-only call that proves the path to veengu.
     * A structured reply (even an error status) means the call reached veengu;
     * {@code reachedVeengu=false} means it failed at discovery/connectivity first.
     */
    @GetMapping("/veengu/participant/{id}/config")
    Map<String, Object> participantConfig(@PathVariable String id) {
        Map<String, Object> result = new HashMap<>();
        result.put("participantId", id);
        try {
            ParticipantConfigDto config = veenguClient.getParticipantConfig(id);
            result.put("ok", true);
            result.put("reachedVeengu", true);
            result.put("config", config);
        } catch (FeignException e) {
            // Reached veengu, but it returned a non-2xx (e.g. unknown participant, or a
            // rejected X-Source-Component). Still proves the end-to-end call path works.
            log.warn("[veengu] getParticipantConfig({}) -> HTTP {}", id, e.status());
            result.put("ok", false);
            result.put("reachedVeengu", true);
            result.put("status", e.status());
            result.put("message", e.getMessage());
        } catch (Exception e) {
            // Never reached veengu — discovery or connectivity failure.
            log.warn("[veengu] getParticipantConfig({}) failed before reaching veengu", id, e);
            result.put("ok", false);
            result.put("reachedVeengu", false);
            result.put("error", e.toString());
        }
        return result;
    }
}
