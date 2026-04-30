package com.innbucks.bookingservice.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.innbucks.bookingservice.client.UserServiceClient;
import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.CustomerTierResponseDTO;
import com.innbucks.bookingservice.dto.TierViolationData;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
@Slf4j
public class TierAccessInterceptor implements HandlerInterceptor {

    private final ObjectMapper objectMapper;
    private final UserServiceClient userServiceClient;

    // Feign client context creation publishes ApplicationEvents that re-enter
    // the WebMvc bean graph; injecting it lazily breaks that cycle so the
    // interceptor can be wired into WebMvcConfig at startup.
    public TierAccessInterceptor(ObjectMapper objectMapper,
                                 @Lazy UserServiceClient userServiceClient) {
        this.objectMapper = objectMapper;
        this.userServiceClient = userServiceClient;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        MinTier minTier = handlerMethod.getMethodAnnotation(MinTier.class);
        if (minTier == null) {
            minTier = handlerMethod.getBeanType().getAnnotation(MinTier.class);
        }
        if (minTier == null) {
            return true;
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String subject = auth != null ? auth.getName() : null;
        // Always read the live tier from user-service so a customer who's just
        // upgraded (but is still presenting an older token) isn't blocked here.
        Integer currentTier = lookupCurrentTier(subject);
        if (currentTier == null) {
            writeError(response, HttpStatus.SERVICE_UNAVAILABLE,
                    "Unable to verify customer tier — user-service is currently unavailable.",
                    null);
            return false;
        }

        if (currentTier >= minTier.value()) {
            return true;
        }

        String message = "You require tier " + minTier.value()
                + " registration to access that feature (current tier: " + currentTier + ")";
        writeError(response, HttpStatus.UNPROCESSABLE_ENTITY, message,
                TierViolationData.builder()
                        .requiredTier(minTier.value())
                        .currentTier(currentTier)
                        .build());
        return false;
    }

    private Integer lookupCurrentTier(String subject) {
        if (subject == null || subject.isBlank()) {
            return 0;
        }
        try {
            ApiResult<CustomerTierResponseDTO> envelope = userServiceClient.lookupCustomerTier(subject);
            if (envelope == null || envelope.getData() == null) {
                return null;
            }
            return envelope.getData().getCurrentTier();
        } catch (Exception e) {
            log.warn("Tier lookup failed subject={} message={}", subject, e.getMessage());
            return null;
        }
    }

    private void writeError(HttpServletResponse response, HttpStatus status, String message, TierViolationData data)
            throws java.io.IOException {
        ApiResult<TierViolationData> body = ApiResult.<TierViolationData>builder()
                .code(String.valueOf(status.value()))
                .message(message)
                .data(data)
                .build();
        response.setContentType("application/json");
        response.setStatus(status.value());
        objectMapper.writeValue(response.getWriter(), body);
    }
}
