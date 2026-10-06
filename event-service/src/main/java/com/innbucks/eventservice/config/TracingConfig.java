package com.innbucks.eventservice.config;

import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

/**
 * Tracing wiring that does not depend on HOW the HTTP clients are built (see
 * CLAUDE.md "Tracing and compression").
 *
 * <ul>
 *   <li><b>Every {@code RestClient.Builder} / {@code RestTemplate} bean is
 *       observed.</b> This service defines its own builder beans (it has no
 *       {@code spring-boot-restclient}, so Boot's observed builder never
 *       existed), and an unobserved client neither records a span nor sends
 *       {@code traceparent}. Applying the registry here, after the bean is
 *       built, keeps working whatever the bean's factory method becomes —
 *       pooled request factory, different timeouts — and is idempotent on a
 *       builder Boot already observed. Clients built from a STATIC
 *       {@code RestClient.builder()} (the partner clients) are not beans and
 *       stay unobserved.</li>
 *   <li><b>The propagator only injects toward the fleet</b>
 *       ({@link FleetOnlyTracePropagator}), so a partner client that IS
 *       observed still sends no trace headers.</li>
 * </ul>
 *
 * Both are {@code static} so they do not pull this configuration (or the
 * registry) into existence before the other post-processors.
 */
@Configuration(proxyBeanMethods = false)
public class TracingConfig {

    @Bean
    static BeanPostProcessor observedHttpClientsPostProcessor(ObjectProvider<ObservationRegistry> registry) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof RestClient.Builder builder) {
                    registry.ifAvailable(builder::observationRegistry);
                } else if (bean instanceof RestTemplate template) {
                    registry.ifAvailable(template::setObservationRegistry);
                }
                return bean;
            }
        };
    }

    @Bean
    static BeanPostProcessor fleetOnlyTracePropagationPostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof Propagator propagator && !(bean instanceof FleetOnlyTracePropagator)) {
                    return new FleetOnlyTracePropagator(propagator);
                }
                return bean;
            }
        };
    }
}
