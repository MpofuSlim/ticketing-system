package com.innbucks.seatservice.config;

import org.h2.server.web.JakartaWebServlet;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Spring Boot 4 removed H2ConsoleAutoConfiguration, so the H2 console
// servlet has to be registered manually. Gated on
// spring.h2.console.enabled so the console only loads in dev.
@Configuration
@ConditionalOnProperty(prefix = "spring.h2.console", name = "enabled", havingValue = "true")
public class H2ConsoleConfig {

    @Bean
    public ServletRegistrationBean<JakartaWebServlet> h2ConsoleServlet() {
        ServletRegistrationBean<JakartaWebServlet> reg =
                new ServletRegistrationBean<>(new JakartaWebServlet(), "/h2-console/*");
        reg.setLoadOnStartup(1);
        return reg;
    }
}
