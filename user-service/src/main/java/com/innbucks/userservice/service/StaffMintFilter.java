package com.innbucks.userservice.service;

import com.innbucks.userservice.config.StaffAccountProperties;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.security.PermissionCatalog;
import com.innbucks.userservice.security.StaffRoles;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The mint-time eligibility filter (V44): the backstop for every way an account
 * that is NOT staff-eligible can come to hold staff authority — a migration,
 * direct SQL, an orphan {@code user_roles} string, a domain removed from the
 * configuration, or a writer nobody thought of.
 *
 * <p>Runs where every token is built: {@code AuthService.buildResponse} (every
 * login, 2FA verify, enrolment, refresh and organization switch) and
 * {@code JwtFilter.permissionsFor} (a token minted before the {@code perms} claim
 * existed, whose permissions are re-derived per request — without the filter
 * there, a grant to such a token's role would take effect with no mint at all).
 *
 * <p>Staff authority = a {@link StaffRoles#NAMED} role name in the roles claim,
 * or any PLATFORM code in the permissions. The wildcard holder (SUPER_ADMIN) is
 * exempt. For anyone else not staff-eligible
 * ({@link StaffEligibility#ineligibility}):
 * <ul>
 *   <li>{@code staff.eligibility-enforcement=watch} (the default): mint exactly
 *       as before and count {@code user.staff.ineligible_holder{reason}} —
 *       {@code unverified}, {@code off_domain} or {@code no_profile}. It reads 0
 *       once every legacy holder has been adopted;</li>
 *   <li>{@code enforce}: drop every PLATFORM code from {@code perms} AND every
 *       NAMED staff role from {@code roles} — booking-service and event-service
 *       grant cross-organizer access by those names, never by the permission.</li>
 * </ul>
 */
@Slf4j
@Component
public class StaffMintFilter {

    /** The roles and permissions a token carries after the filter. */
    public record Minted(List<String> roles, List<String> permissions) {
    }

    public static final String METRIC = "user.staff.ineligible_holder";

    private final StaffEligibility eligibility;
    private final StaffAccountProperties properties;
    private final Map<StaffEligibility.Ineligibility, Counter> counters =
            new EnumMap<>(StaffEligibility.Ineligibility.class);

    public StaffMintFilter(StaffEligibility eligibility, StaffAccountProperties properties) {
        this.eligibility = eligibility;
        this.properties = properties;
    }

    /** Registers every reason's series at zero, so the first increase is visible to an alert. */
    @Autowired(required = false)
    void setMeterRegistry(MeterRegistry registry) {
        if (registry == null) return;
        for (StaffEligibility.Ineligibility reason : StaffEligibility.Ineligibility.values()) {
            counters.put(reason, Counter.builder(METRIC)
                    .description("Tokens minted for a holder of staff authority who is not staff-eligible")
                    .tag("reason", reason.tag())
                    .register(registry));
        }
    }

    /**
     * Filters what a token for {@code user} would carry. Returns the input
     * unchanged unless the account holds staff authority, is not SUPER_ADMIN, is
     * not staff-eligible AND enforcement is on.
     */
    public Minted apply(User user, Collection<String> roles, Collection<String> permissions) {
        List<String> roleList = roles == null ? List.of() : List.copyOf(roles);
        List<String> permissionList = permissions == null ? List.of() : List.copyOf(permissions);
        if (user == null || user.hasRole(User.Role.SUPER_ADMIN)) {
            return new Minted(roleList, permissionList);
        }
        boolean staffAuthority = roleList.stream().anyMatch(StaffRoles::isNamed)
                || permissionList.stream().anyMatch(StaffMintFilter::platform);
        if (!staffAuthority) {
            return new Minted(roleList, permissionList);
        }
        Optional<StaffEligibility.Ineligibility> why = eligibility.ineligibility(user);
        if (why.isEmpty()) {
            return new Minted(roleList, permissionList);
        }
        Counter counter = counters.get(why.get());
        if (counter != null) counter.increment();
        if (!properties.enforcing()) {
            log.debug("Staff authority minted for an ineligible holder (watch mode) userId={} reason={}",
                    user.getId(), why.get().tag());
            return new Minted(roleList, permissionList);
        }
        List<String> keptRoles = new ArrayList<>();
        for (String role : roleList) {
            if (!StaffRoles.isNamed(role)) keptRoles.add(role);
        }
        List<String> keptPermissions = new ArrayList<>();
        for (String code : permissionList) {
            if (!platform(code)) keptPermissions.add(code);
        }
        log.info("Staff authority withheld from an ineligible holder userId={} reason={}",
                user.getId(), why.get().tag());
        return new Minted(List.copyOf(keptRoles), List.copyOf(keptPermissions));
    }

    private static boolean platform(String code) {
        return PermissionCatalog.scopeOf(code) == PermissionCatalog.Scope.PLATFORM;
    }
}
