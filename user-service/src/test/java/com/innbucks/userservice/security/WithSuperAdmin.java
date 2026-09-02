package com.innbucks.userservice.security;

import org.springframework.security.test.context.support.WithSecurityContext;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Authenticates a MockMvc test as a real SUPER_ADMIN: the {@code ROLE_SUPER_ADMIN}
 * authority AND the permissions that role actually carries.
 *
 * <p>Replaces {@code @WithMockUser(roles = "SUPER_ADMIN")} on the admin
 * controller tests, which since V35 modelled a caller that cannot exist. A real
 * SUPER_ADMIN reaches a controller with both: {@code JwtFilter} grants the
 * permissions from the token's {@code perms} claim, or — for a token minted
 * before that claim existed — re-derives them from the roles claim. There is no
 * path that produces the role alone, so a fixture granting only the role tests
 * an unreachable state and fails against endpoints that (correctly) gate on a
 * permission.
 *
 * <p>The authorities are computed from {@link PermissionCatalog} by
 * {@link WithSuperAdminSecurityContextFactory} rather than listed here, which is
 * why this is a {@code @WithSecurityContext} annotation and not a
 * {@code @WithMockUser} alias — an annotation's attributes must be compile-time
 * constants, so a hardcoded list would silently go stale the first time someone
 * added a permission, reintroducing exactly the 403 this fixes.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
@WithSecurityContext(factory = WithSuperAdminSecurityContextFactory.class)
public @interface WithSuperAdmin {

    /** The authenticated principal's name, as {@code Authentication#getName()}. */
    String value() default "admin@innbucks.co.zw";
}
