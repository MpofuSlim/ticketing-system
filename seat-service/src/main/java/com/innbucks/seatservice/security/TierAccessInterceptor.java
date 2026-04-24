package com.innbucks.seatservice.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class TierAccessInterceptor implements HandlerInterceptor {

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
        int currentTier = currentTier(auth);
        if (currentTier >= minTier.value()) {
            return true;
        }

        response.setContentType("application/json");
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        String message = "You require tier " + minTier.value()
                + " registration to access that feature (current tier: " + currentTier + ")";
        response.getWriter().write(
                "{\"code\":\"403 FORBIDDEN\",\"message\":\"" + message + "\",\"data\":null}");
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
