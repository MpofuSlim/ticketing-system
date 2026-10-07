package com.innbucks.seatservice.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redis calls in this service run inside database transactions (the seat-lock
 * owner put/get/delete in SeatService), holding a pooled connection and the
 * seat's row lock while Redis answers. Lettuce's default command timeout is
 * 60s, so the bound in this module's REAL application.yaml is what keeps a
 * slow Redis from holding them for a minute.
 */
class RedisTimeoutConfigTest {

    @Test
    void redisCommandAndConnectTimeoutsAreBoundedByDefault() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .run(context -> {
                    Binder binder = Binder.get(context.getEnvironment());
                    assertThat(binder.bind("spring.data.redis.timeout", Duration.class).get())
                            .isEqualTo(Duration.ofMillis(500));
                    assertThat(binder.bind("spring.data.redis.connect-timeout", Duration.class).get())
                            .isEqualTo(Duration.ofSeconds(1));
                });
    }
}
