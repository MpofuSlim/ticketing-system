package com.innbucks.userservice.support;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** Wires customer support: {@link SupportProperties}, its clock, the classifier and the assertion signer. */
@Configuration
@EnableConfigurationProperties(SupportProperties.class)
public class SupportConfig {

    /** UTC: every support timestamp is a UTC {@code LocalDateTime} (the fleet rule), rendered at the edge. */
    @Bean
    public Clock supportClock() {
        return Clock.systemUTC();
    }

    @Bean
    public SupportQueryClassifier supportQueryClassifier(@Value("${innbucks.country:ZW}") String country) {
        return new SupportQueryClassifier(country);
    }

    /** Throws at boot on a malformed or short key — never at the first support call. */
    @Bean
    public SupportAssertionSigner supportAssertionSigner(SupportProperties properties,
                                                         @Qualifier("supportClock") Clock supportClock) {
        return new SupportAssertionSigner(properties.getAssertion(), supportClock);
    }
}
