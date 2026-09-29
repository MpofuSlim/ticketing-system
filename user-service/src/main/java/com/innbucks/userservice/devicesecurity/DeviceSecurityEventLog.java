package com.innbucks.userservice.devicesecurity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.innbucks.userservice.devicesecurity.entity.CustomerDevice;
import com.innbucks.userservice.devicesecurity.entity.DeviceSecurityEvent;
import com.innbucks.userservice.devicesecurity.repository.DeviceSecurityEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Writes the device-security decision log (§3 rule 9). Joins the caller's
 * transaction on purpose, so a state change and the row explaining it commit —
 * or roll back — together. Callers must not put the OTP, a token, a ticket or a
 * PIN in {@code features} or {@code note}.
 */
@Component
@Slf4j
public class DeviceSecurityEventLog {

    private final DeviceSecurityEventRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public DeviceSecurityEventLog(DeviceSecurityEventRepository repository, ObjectMapper objectMapper,
                                  Clock deviceSecurityClock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = deviceSecurityClock;
    }

    @Transactional
    public DeviceSecurityEvent record(SecurityEventType type, ActorType actor, String actorId,
                                      String msisdn, CustomerDevice device,
                                      Consumer<DeviceSecurityEvent.DeviceSecurityEventBuilder> details) {
        DeviceSecurityEvent.DeviceSecurityEventBuilder b = DeviceSecurityEvent.builder()
                .occurredAt(LocalDateTime.now(clock))
                .eventType(type.name())
                .actorType(actor.name())
                .actorId(truncate(actorId, 255))
                .msisdn(msisdn);
        if (device != null) {
            b.deviceId(device.getPublicId()).installIdHash(device.getInstallIdHash());
        }
        if (details != null) {
            details.accept(b);
        }
        return repository.save(b.build());
    }

    /** Serialises a feature map for the {@code features} column. Never throws. */
    public String json(Map<String, ?> features) {
        if (features == null || features.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(features);
        } catch (JsonProcessingException e) {
            log.warn("Device-security features could not be serialised: {}", e.getMessage());
            return "{\"_error\":\"features-serialisation-failed\"}";
        }
    }

    static String truncate(String v, int max) {
        return v == null || v.length() <= max ? v : v.substring(0, max);
    }
}
