package com.innbucks.bookingservice.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.TierViolationData;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
@RequiredArgsConstructor
public class TierAccessInterceptor implements HandlerInterceptor {

    private final ObjectMapper objectMapper;
    private final LiveTierService liveTierService;

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
        // Live-tier lookup wins over the JWT claim so a tier upgrade made
        // mid-session is honored immediately. Falls back to the JWT claim
        // only if user-service is unreachable, so an outage in user-service
        // doesn't lock every authenticated caller out.
        Integer liveTier = auth == null ? null
                : liveTierService.tierForSubject(String.valueOf(auth.getPrincipal()));
        int currentTier = liveTier != null ? liveTier : currentTier(auth);
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

    private int currentTier(Authentication auth) {
        if (auth == null) return 0;
        int tier = 0;
        for (var authority : auth.getAuthorities()) {
            String name = authority.getAuthority();
            if (name != null && name.startsWith("TIER_")) {
                try {
                    tier = Math.max(tier, Integer.parseInt(name.substring(5)));
                } catch (NumberFormatException ignored) { }
            }
        }
        return tier;
    }
}
