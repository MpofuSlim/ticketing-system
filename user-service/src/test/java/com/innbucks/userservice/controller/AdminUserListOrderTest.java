package com.innbucks.userservice.controller;

import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.exception.GlobalExceptionHandler;
import com.innbucks.userservice.repository.TenantProfileRepository;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.service.MfaService;
import com.innbucks.userservice.service.UserAdminService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.Matchers.contains;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /admin/users} returns its rows in {@link com.innbucks.userservice.service.AdminUserOrder}'s
 * order on EVERY branch — with and without {@code active}, with and without
 * {@code includeCustomers} — through real dispatch (standalone MockMvc). Each
 * repository query answers in a scrambled order, so the sort has to happen in
 * the controller for the assertion to hold.
 */
class AdminUserListOrderTest {

    private MockMvc mvc;

    private static User user(long id, String first, String last, User.Role... roles) {
        return User.builder().id(id).firstName(first).lastName(last).email("u" + id + "@example.co.zw")
                .roles(User.roleNames(roles)).active(true).build();
    }

    /** What every query returns: owners, mixed case and a nameless account, scrambled. */
    private static List<User> scrambled() {
        return new ArrayList<>(List.of(
                user(7, "Bob", "Banda", User.Role.TEAM_MEMBER),
                user(4, " ", "Nameless", User.Role.SHOP_ADMIN),
                user(2, "Zoe", "Zulu", User.Role.SUPER_ADMIN),
                user(3, "alice", "Moyo", User.Role.EVENT_ORGANIZER),
                user(1, "bob", "Banda", User.Role.MERCHANT_ADMIN),
                user(6, "Anesu", "Owner", User.Role.SUPER_ADMIN),
                user(5, "Alice", "Dube", User.Role.EVENT_ORGANIZER)));
    }

    @BeforeEach
    void setUp() {
        UserRepository users = mock(UserRepository.class);
        when(users.findAll()).thenReturn(scrambled());
        when(users.findByActive(anyBoolean())).thenReturn(scrambled());
        when(users.findAllExceptOnlyRole(eq(User.Role.CUSTOMER.name()))).thenReturn(scrambled());
        when(users.findByActiveExceptOnlyRole(anyBoolean(), eq(User.Role.CUSTOMER.name()))).thenReturn(scrambled());
        TenantProfileRepository profiles = mock(TenantProfileRepository.class);
        when(profiles.findByUserIdIn(any())).thenReturn(List.of());

        mvc = MockMvcBuilders.standaloneSetup(new AdminUserController(users, profiles,
                        mock(UserAdminService.class), mock(MfaService.class)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @ParameterizedTest(name = "GET /admin/users{0}")
    @ValueSource(strings = {"", "?active=true", "?active=false", "?includeCustomers=true",
            "?active=true&includeCustomers=true", "?active=false&includeCustomers=true"})
    void everyBranchReturnsOwnersFirstThenByName(String query) throws Exception {
        mvc.perform(get("/admin/users" + query))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].id").value(contains(6, 2, 5, 3, 1, 7, 4)));
    }
}
