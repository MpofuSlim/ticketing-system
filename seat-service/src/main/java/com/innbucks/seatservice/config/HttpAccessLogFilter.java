package com.innbucks.seatservice.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

@Configuration
@Slf4j
public class HttpAccessLogFilter {

    private static final Set<String> SKIP_PREFIXES = Set.of(
            "/actuator", "/swagger-ui", "/v3/api-docs", "/h2-console", "/favicon.ico"
    );

    @Bean
    public FilterRegistrationBean<OncePerRequestFilter> httpAccessLogFilterRegistration() {
        FilterRegistrationBean<OncePerRequestFilter> reg = new FilterRegistrationBean<>();
        reg.setFilter(new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                String path = request.getRequestURI();
                if (skip(path)) {
                    chain.doFilter(request, response);
                    return;
                }
                long start = System.currentTimeMillis();
                String method = request.getMethod();
                String query = request.getQueryString();
                String fullPath = query == null ? path : path + "?" + query;
                log.info(">> {} {}", method, fullPath);
                try {
                    chain.doFilter(request, response);
                } finally {
                    long ms = System.currentTimeMillis() - start;
                    log.info("<< {} {} status={} duration={}ms", method, fullPath, response.getStatus(), ms);
                }
            }
        });
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        reg.addUrlPatterns("/*");
        return reg;
    }

    private static boolean skip(String path) {
        for (String p : SKIP_PREFIXES) if (path.startsWith(p)) return true;
        return false;
    }
}
