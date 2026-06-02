package innbucks.paymentservice.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Bean-resolution test for the IdempotencyStore wiring.
 *
 * <p>Both implementations sit in the application context — {@code InMemoryIdempotencyStore}
 * is an unconditional {@code @Component}, and {@code RedisIdempotencyStore} is
 * {@code @Primary @ConditionalOnProperty(name="app.idempotency.store", havingValue="redis")}.
 * The Idempotency-Key replay guarantee at horizontal scale relies on the {@code redis}
 * property activating Redis; this test pins that contract so the wiring can't silently
 * regress (e.g. if the {@code @ConditionalOnProperty} value is renamed, the {@code @Primary}
 * is dropped, or the docker-compose env var is removed).
 *
 * <p>Uses a bare {@link AnnotationConfigApplicationContext} instead of {@code @SpringBootTest}
 * to keep the test fast and isolated from the rest of payment-service's autoconfig
 * (Hibernate, Flyway, Kafka, etc.) — none of which is relevant to the wiring question.
 */
class IdempotencyStoreWiringTest {

    @Test
    void underProdProperty_wiredStoreIsRedis() {
        try (AnnotationConfigApplicationContext ctx = buildContext("redis")) {
            // Single IdempotencyStore must be injectable (no NoUniqueBeanDefinitionException);
            // @Primary on RedisIdempotencyStore is what makes that true when both beans exist.
            IdempotencyStore wired = ctx.getBean(IdempotencyStore.class);
            assertThat(wired).isInstanceOf(RedisIdempotencyStore.class);

            // Belt-and-braces: if a refactor deletes the InMemory bean the test above
            // still passes hollowly, so assert both impls really are in the context.
            assertThat(ctx.getBean(RedisIdempotencyStore.class)).isNotNull();
            assertThat(ctx.getBean(InMemoryIdempotencyStore.class)).isNotNull();
        }
    }

    @Test
    void withoutProdProperty_wiredStoreIsInMemory() {
        try (AnnotationConfigApplicationContext ctx = buildContext("memory")) {
            // Property != "redis" -> Redis bean isn't registered, InMemory is the sole choice.
            IdempotencyStore wired = ctx.getBean(IdempotencyStore.class);
            assertThat(wired).isInstanceOf(InMemoryIdempotencyStore.class);
            assertThat(ctx.getBeansOfType(RedisIdempotencyStore.class)).isEmpty();
        }
    }

    private static AnnotationConfigApplicationContext buildContext(String storeProperty) {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        // Property must be in the environment BEFORE component classes are registered, so
        // @ConditionalOnProperty sees it during bean-condition evaluation.
        ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                "wiring-test", Map.of("app.idempotency.store", storeProperty)));
        registerWithoutAutowire(ctx, InMemoryIdempotencyStore.class);
        registerWithoutAutowire(ctx, RedisIdempotencyStore.class);
        ctx.register(MockRedisConfig.class);
        ctx.refresh();
        return ctx;
    }

    private static void registerWithoutAutowire(GenericApplicationContext ctx, Class<?> beanClass) {
        ctx.registerBean(beanClass.getName(), beanClass);
    }

    @Configuration
    static class MockRedisConfig {
        @Bean
        StringRedisTemplate stringRedisTemplate() {
            return mock(StringRedisTemplate.class);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
