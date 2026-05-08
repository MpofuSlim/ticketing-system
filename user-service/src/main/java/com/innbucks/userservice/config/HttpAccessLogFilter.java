package com.innbucks.userservice.config;

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
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

                ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);
                try {
                    chain.doFilter(request, wrappedResponse);
                } finally {
                    long ms = System.currentTimeMillis() - start;
                    byte[] responseBody = wrappedResponse.getContentAsByteArray();
                    String body = responseBody.length > 0
                            ? new String(responseBody, StandardCharsets.UTF_8)
                            : "";

                    if (body.length() > 500) {
                        body = body.substring(0, 500) + "...";
                    }

                    String logMessage = body.isEmpty()
                            ? String.format("<< %s %s status=%d duration=%dms", method, fullPath, wrappedResponse.getStatus(), ms)
                            : String.format("<< %s %s status=%d duration=%dms body=%s", method, fullPath, wrappedResponse.getStatus(), ms, body);
                    log.info(logMessage);
                    wrappedResponse.copyBodyToResponse();
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
