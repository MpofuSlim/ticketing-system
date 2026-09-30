package com.innbucks.userservice.service;

import com.innbucks.userservice.entity.StaffProfile;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.StaffProfileRepository;
import com.innbucks.userservice.repository.UserRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@code user.staff.invariant_breach{kind}} (V44): how many staff-profiled
 * accounts break one of the three invariants every writer is meant to keep —
 * {@code org_membership} (belongs to an ACTIVE organization),
 * {@code non_staff_role} (holds a role that is not a staff role),
 * {@code login_phone} (invite accepted, yet still has a sign-in phone). The
 * writers refuse each of these and the mint withholds organization claims, so
 * any value above 0 means a writer nobody thought of, direct SQL, or a role
 * edited out from under a profiled holder. Alert when above 0.
 *
 * <p><b>No {@code @SchedulerLock}, deliberately</b>: the job is READ-ONLY — three
 * counts and a per-replica gauge — so N replicas running it at once is harmless
 * and each replica exporting its own reading is exactly what a gauge wants
 * (the alert takes the max). Refreshed every 15 minutes.
 */
@Slf4j
@Component
public class StaffInvariantGauge {

    public static final String METRIC = "user.staff.invariant_breach";

    private final StaffProfileRepository profiles;
    private final UserRepository users;
    private final RoleGrantGuard guard;
    private final Map<String, AtomicLong> readings = Map.of(
            "org_membership", new AtomicLong(),
            "non_staff_role", new AtomicLong(),
            "login_phone", new AtomicLong());

    public StaffInvariantGauge(StaffProfileRepository profiles, UserRepository users, RoleGrantGuard guard) {
        this.profiles = profiles;
        this.users = users;
        this.guard = guard;
    }

    @Autowired(required = false)
    void setMeterRegistry(MeterRegistry registry) {
        if (registry == null) return;
        readings.forEach((kind, value) -> Gauge.builder(METRIC, value, AtomicLong::get)
                .description("Staff-profiled accounts breaking a staff invariant")
                .tag("kind", kind)
                .register(registry));
    }

    // Read-only on every replica — see the class javadoc for why this carries no lock.
    @Scheduled(fixedDelayString = "${staff.invariant-gauge.interval:PT15M}",
            initialDelayString = "${staff.invariant-gauge.initial-delay:PT2M}")
    @Transactional(readOnly = true)
    public void refresh() {
        try {
            readings.get("org_membership").set(profiles.countWithActiveOrganization());
            readings.get("login_phone").set(profiles.countAcceptedWithLoginPhone());
            List<Long> ids = profiles.findAll().stream().map(StaffProfile::getUserId).toList();
            long nonStaff = 0;
            for (User user : users.findAllById(ids)) {
                if (!guard.nonStaffRoles(user).isEmpty()) nonStaff++;
            }
            readings.get("non_staff_role").set(nonStaff);
            long total = readings.values().stream().mapToLong(AtomicLong::get).sum();
            if (total > 0) {
                log.warn("Staff invariant breaches: org_membership={} non_staff_role={} login_phone={}",
                        readings.get("org_membership").get(), nonStaff, readings.get("login_phone").get());
            }
        } catch (RuntimeException ex) {
            log.warn("Staff invariant gauge refresh failed: {}", ex.toString());
        }
    }

    /** The last reading of one kind — for tests. */
    public long reading(String kind) {
        return readings.get(kind).get();
    }
}
