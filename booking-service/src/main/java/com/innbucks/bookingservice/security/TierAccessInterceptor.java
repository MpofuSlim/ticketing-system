package com.innbucks.bookingservice.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.innbucks.bookingservice.client.UserServiceClient;
import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.CustomerTierResponseDTO;
import com.innbucks.bookingservice.dto.TierViolationData;
import com.innbucks.bookingservice.util.MsisdnMasking;
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

    // Request attribute the interceptor stamps with the live DB tier so
    // controllers don't have to re-call user-service. Read by BookingController
    // when it needs the tier for per-tier seat-count limits.
    public static final String CURRENT_TIER_ATTRIBUTE = "innbucks.currentTier";

    private final ObjectMapper objectMapper;
    private final UserServiceClient userServiceClient;

    // @Lazy on the Feign client breaks a context-init cycle: WebMvcConfig ->
    // TierAccessInterceptor -> UserServiceClient (Feign) -> WebMvcConfig.
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
        int currentTier = resolveCurrentTier(auth);
        request.setAttribute(CURRENT_TIER_ATTRIBUTE, currentTier);
        if (currentTier >= minTier.value()) {
            return true;
        }

        String message = "You require tier " + minTier.value()
                + " registration to access that feature (current tier: " + currentTier + ")";
        ApiResult<TierViolationData> body = ApiResult.<TierViolationData>builder()
                .code(String.valueOf(HttpStatus.UNPROCESSABLE_ENTITY.value()))
                .message(message)
                .data(TierViolationData.builder()
                        .requiredTier(minTier.value())
                        .currentTier(currentTier)
                        .build())
                .build();

        response.setContentType("application/json");
        response.setStatus(HttpStatus.UNPROCESSABLE_ENTITY.value());
        objectMapper.writeValue(response.getWriter(), body);
        return false;
    }

    // Tier is sourced from user-service (the system of record) rather than the
    // JWT's `tier` claim. The claim goes stale the moment a customer upgrades
    // — they'd have to re-login or refresh before tier-gated endpoints would
    // accept them. Looking it up live keeps the gate honest at the cost of an
    // extra hop per @MinTier request.
    private int resolveCurrentTier(Authentication auth) {
        if (auth == null) return 0;
        String phoneNumber = null;
        if (auth.getDetails() instanceof JwtAuthDetails details) {
            phoneNumber = details.phoneNumber();
        }
        if (phoneNumber == null || phoneNumber.isBlank()) {
            return 0;
        }
        try {
            ApiResult<CustomerTierResponseDTO> result = userServiceClient.getCustomerTier(phoneNumber);
            if (result == null || result.getData() == null) {
                return 0;
            }
            return result.getData().getCurrentTier();
        } catch (Exception ex) {
            log.warn("Failed to resolve customer tier from user-service phoneNumber={} message={}",
                    MsisdnMasking.mask(phoneNumber), ex.getMessage());
            return 0;
        }
    }
}
