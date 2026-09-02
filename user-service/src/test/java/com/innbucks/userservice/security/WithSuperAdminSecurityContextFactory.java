package com.innbucks.userservice.security;

import com.innbucks.userservice.entity.User;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithSecurityContextFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the {@link SecurityContext} for {@link WithSuperAdmin}, mirroring what
 * {@code JwtFilter} grants a real SUPER_ADMIN request.
 */
public class WithSuperAdminSecurityContextFactory
        implements WithSecurityContextFactory<WithSuperAdmin> {

    @Override
    public SecurityContext createSecurityContext(WithSuperAdmin annotation) {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_" + User.Role.SUPER_ADMIN.name()));

        // Exactly what PermissionResolver expands the '*' wildcard to, read from
        // the live catalog so a permission added later is granted here too —
        // without this the fixture would drift from the code the day someone
        // adds one, and the failure would look like a broken endpoint.
        for (String permission : PermissionCatalog.concrete()) {
            authorities.add(new SimpleGrantedAuthority(permission));
        }

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(
                new UsernamePasswordAuthenticationToken(annotation.value(), null, authorities));
        return context;
    }
}
