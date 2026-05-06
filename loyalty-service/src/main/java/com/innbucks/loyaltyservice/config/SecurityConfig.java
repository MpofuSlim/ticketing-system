package com.innbucks.loyaltyservice.config;

import org.h2.server.web.JakartaWebServlet;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    /**
     * The loyalty service sits behind the API gateway, which authenticates the
     * caller upstream and forwards trusted headers. We keep CSRF off (stateless
     * REST), allow actuator, swagger, and the loyalty API; everything else
     * still requires authentication if it ever gets exposed directly.
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/actuator/**",
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/loyalty/**",
                                "/h2-console/**"
                        ).permitAll()
                        .anyRequest().authenticated()
                )
                .httpBasic(b -> {});
        return http.build();
    }

    /**
     * Spring Boot 4's H2ConsoleAutoConfiguration wasn't registering the servlet
     * here (the request fell through to DispatcherServlet and 404-ed as a
     * missing static resource). Registering manually as a ServletRegistrationBean
     * mounts H2's console at /h2-console/* unambiguously.
     */
    @Bean
    public ServletRegistrationBean<JakartaWebServlet> h2ConsoleServlet() {
        ServletRegistrationBean<JakartaWebServlet> bean =
                new ServletRegistrationBean<>(new JakartaWebServlet(), "/h2-console/*");
        bean.setLoadOnStartup(1);
        return bean;
    }
}
