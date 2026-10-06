package com.innbucks.userservice.devicesecurity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/** Wiring for DTX device security. */
@Configuration
@EnableConfigurationProperties(DeviceSecurityProperties.class)
public class DeviceSecurityConfig {

    /** Response header the contract asks for on every DTX call (§5). */
    public static final String TRACE_HEADER = "x-trace-id";

    /**
     * UTC clock for every device-security timestamp — injected rather than a bare
     * {@code now()} so the time-window rules (ladders, cooling, grace, trust) are
     * testable to the second.
     */
    @Bean
    public Clock deviceSecurityClock() {
        return Clock.systemUTC();
    }

    @Bean
    public StagingClientServiceClient stagingClientServiceClient(DeviceSecurityProperties properties,
                                                                 Clock deviceSecurityClock) {
        return new StagingClientServiceClient(properties.getStaging(), deviceSecurityClock);
    }

    /** Parses the ticket key at boot, so a malformed key fails the deploy, not the first sign-in. */
    @Bean
    public LoginTicketSigner loginTicketSigner(DeviceSecurityProperties properties, Clock deviceSecurityClock) {
        return new LoginTicketSigner(properties.getTicket(), deviceSecurityClock);
    }

    /**
     * Security notifications ride their own pool, separate from the admin-sized
     * {@code notificationExecutor}: these scale with customer traffic (every new
     * phone, every block), and a burst of them must not queue behind — or starve —
     * credential delivery. CallerRuns on saturation: slower, never dropped.
     */
    @Bean(name = "deviceSecurityExecutor")
    public Executor deviceSecurityExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("device-notify-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // Same trace on the worker as on the sign-in that queued the notice.
        executor.setTaskDecorator(new ContextPropagatingTaskDecorator());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /**
     * Echoes {@code x-trace-id} on every DTX response: the caller's own value when
     * it sent one, else this request's correlation id — so the app, the broker and
     * support can quote the same id for one sign-in.
     */
    @Bean
    public FilterRegistrationBean<OncePerRequestFilter> deviceSecurityTraceFilter() {
        FilterRegistrationBean<OncePerRequestFilter> reg = new FilterRegistrationBean<>();
        reg.setFilter(new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                String trace = request.getHeader(TRACE_HEADER);
                if (trace == null || trace.isBlank() || trace.length() > 128) {
                    trace = MDC.get("correlationId");
                }
                if (trace != null) {
                    response.setHeader(TRACE_HEADER, trace);
                }
                chain.doFilter(request, response);
            }
        });
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        reg.addUrlPatterns("/auth/client-service", "/auth/client-service/*", "/auth/devices", "/auth/devices/*",
                "/auth/device/*", "/device-security/*");
        return reg;
    }
}
