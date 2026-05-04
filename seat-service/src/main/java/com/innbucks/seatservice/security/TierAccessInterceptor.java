package com.innbucks.seatservice.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.innbucks.seatservice.client.UserServiceClient;
import com.innbucks.seatservice.dto.ApiResult;
import com.innbucks.seatservice.dto.CustomerTierResponseDTO;
import com.innbucks.seatservice.dto.TierViolationData;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class TierAccessInterceptor implements HandlerInterceptor {

    private final ObjectMapper objectMapper;
    private final UserServiceClient userServiceClient;

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

    // Tier comes from user-service (the system of record), not the JWT claim,
    // so an upgrade lands immediately without forcing the customer to re-login.
    // One extra hop per @MinTier request — acceptable for these gated flows.
    private int resolveCurrentTier(Authentication auth) {
        if (auth == null) return 0;
        String phoneNumber = null;
        if (auth.getDetails() instanceof JwtAuthDetails details) {
            phoneNumber = details.phoneNumber();
        }
        if (phoneNumber == null || phoneNumber.isBlank()) {
            return 0;
        }
        Optional<CustomerTierResponseDTO> result = userServiceClient.getCustomerTier(phoneNumber);
        return result.map(CustomerTierResponseDTO::getCurrentTier).orElse(0);
    }
}
