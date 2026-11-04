package com.innbucks.userservice.entity;

import jakarta.persistence.Entity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lombok's {@code @Builder} ignores a field's initializer unless the field also
 * carries {@code @Builder.Default}. {@code User.createdAt} and
 * {@code Device.registeredAt} had an initializer and no {@code @Builder.Default},
 * and every account and device is created through the builder, so every row was
 * stored with no creation time: the console's "Registered" column was blank on
 * every account.
 */
class UserCreationTimeTest {

    @Test
    void anAccountBuiltWithTheBuilder_hasItsCreationTime_inUtc() {
        LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC);

        User user = User.builder().firstName("Tariro").lastName("Moyo").build();

        assertThat(user.getCreatedAt()).isNotNull();
        assertThat(Duration.between(before, user.getCreatedAt()).abs()).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void aDeviceBuiltWithTheBuilder_hasItsRegistrationTime_inUtc() {
        LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC);

        Device device = Device.builder().deviceId("ios-1").build();

        assertThat(device.getRegisteredAt()).isNotNull();
        assertThat(Duration.between(before, device.getRegisteredAt()).abs()).isLessThan(Duration.ofSeconds(5));
    }

    /**
     * The same trap anywhere else: for every entity with a builder, a field the
     * no-args constructor fills must not come out of the builder empty. A field
     * whose initializer equals the type's own default (false, 0) passes, since
     * the builder agrees with it.
     */
    @Test
    void noEntityBuilder_dropsAFieldInitializer() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));

        List<String> checked = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents("com.innbucks.userservice")) {
            Class<?> type = Class.forName(candidate.getBeanClassName());
            Method builder = staticBuilder(type);
            Constructor<?> noArgs = noArgsConstructor(type);
            if (builder == null || noArgs == null) {
                continue;
            }
            Object built = builder.getReturnType().getMethod("build").invoke(builder.invoke(null));
            Object constructed = noArgs.newInstance();
            checked.add(type.getSimpleName());
            for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field field : c.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) {
                        continue;
                    }
                    field.setAccessible(true);
                    Object fromConstructor = field.get(constructed);
                    Object fromBuilder = field.get(built);
                    boolean lost = field.getType().isPrimitive()
                            ? !fromConstructor.equals(fromBuilder)
                            : fromConstructor != null && fromBuilder == null;
                    if (lost) {
                        dropped.add(type.getSimpleName() + "." + field.getName());
                    }
                }
            }
        }

        assertThat(checked).contains("User", "Device");
        assertThat(dropped)
                .as("fields whose initializer the builder ignores: add @Builder.Default")
                .isEmpty();
    }

    private static Method staticBuilder(Class<?> type) {
        try {
            Method m = type.getDeclaredMethod("builder");
            return Modifier.isStatic(m.getModifiers()) ? m : null;
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static Constructor<?> noArgsConstructor(Class<?> type) {
        try {
            Constructor<?> c = type.getDeclaredConstructor();
            c.setAccessible(true);
            return c;
        } catch (NoSuchMethodException e) {
            return null;
        }
    }
}
