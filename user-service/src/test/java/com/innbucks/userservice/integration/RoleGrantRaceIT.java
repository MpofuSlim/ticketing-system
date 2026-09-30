package com.innbucks.userservice.integration;

import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.testsupport.StaffItSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The race the {@code roles} row locks close (V44 §2.4): at the same instant,
 * one administrator gives a NON-staff account a custom role that grants nothing
 * platform-wide, and another adds a PLATFORM code to that role. Each check alone
 * passes against the state it read — the role isn't staff yet; the account
 * doesn't hold it yet — and together they would leave an ineligible account
 * holding staff authority. {@code SELECT ... FOR UPDATE} on the role rows (in
 * name order, before any read) serialises them, so the second always sees the
 * first's commit and is refused. Repeated, because a race is.
 */
class RoleGrantRaceIT extends StaffItSupport {

    private static final int ROUNDS = 12;

    @Test
    void aConcurrentGrantAndPermissionEditNeverBothSucceed() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        int grantWon = 0;
        int editWon = 0;
        try {
            for (int round = 0; round < ROUNDS; round++) {
                String role = "RACE_" + unique().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "X");
                mockMvc.perform(post("/admin/roles").with(authentication(staffAdmin()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of("name", role,
                                        "description", "Shop staff reader", "permissions", List.of("shop-staff:read")))))
                        .andExpect(status().isCreated());
                User target = customer();

                CyclicBarrier barrier = new CyclicBarrier(2);
                Future<Integer> grant = pool.submit(() -> {
                    barrier.await();
                    return mockMvc.perform(put("/admin/users/{id}/roles", target.getId())
                                    .with(authentication(staffAdmin())).contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(Map.of("roles", List.of("CUSTOMER", role)))))
                            .andReturn().getResponse().getStatus();
                });
                Future<Integer> edit = pool.submit(() -> {
                    barrier.await();
                    return mockMvc.perform(put("/admin/roles/{name}/permissions", role)
                                    .with(authentication(staffAdmin())).contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(Map.of("permissions",
                                            List.of("shop-staff:read", "users:read")))))
                            .andReturn().getResponse().getStatus();
                });
                int g = grant.get(60, TimeUnit.SECONDS);
                int e = edit.get(60, TimeUnit.SECONDS);

                assertThat(List.of(g, e)).as("round %d: grant=%d edit=%d", round, g, e)
                        .containsExactlyInAnyOrder(200, 400);
                if (g == 200) grantWon++; else editWon++;

                // And the database agrees: never an ineligible holder of a staff role.
                long holds = count("SELECT count(*) FROM user_roles WHERE user_id = ?1 AND role = ?2",
                        target.getId(), role);
                long platform = count("SELECT count(*) FROM role_permissions WHERE role_name = ?1 "
                        + "AND permission_code = 'users:read'", role);
                assertThat(holds + platform).as("round %d", round).isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
        }
        // Both orders were observed or not — either way every round had exactly one winner.
        assertThat(grantWon + editWon).isEqualTo(ROUNDS);
    }
}
