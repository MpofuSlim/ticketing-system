package com.innbucks.userservice.controller;

import com.innbucks.userservice.entity.StaffProfile;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.exception.GlobalExceptionHandler;
import com.innbucks.userservice.repository.StaffProfileRepository;
import com.innbucks.userservice.repository.TenantProfileRepository;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.security.PermissionCatalog;
import com.innbucks.userservice.service.MfaService;
import com.innbucks.userservice.service.UserAdminService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Arrays;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A staff account has no sign-in phone (accepting the invite moves the number
 * to the staff profile), so the System Users list showed a dash for every staff
 * member. {@code GET /admin/users} now adds the staff profile's contact number,
 * as {@code contactPhone}, but only for a caller who may read the Staff tab
 * ({@code staff:read}, wildcard-reserved). {@code users:read} alone can sit on a
 * custom role, which must not get every staff member's personal number.
 */
class AdminUserListContactPhoneTest {

    private MockMvc mvc;
    private StaffProfileRepository staffProfiles;

    private static User user(long id, String first, String email, String phone, User.Role role) {
        return User.builder().id(id).firstName(first).lastName("Test").email(email).phoneNumber(phone)
                .roles(User.roleNames(role)).active(true).build();
    }

    @BeforeEach
    void setUp() {
        List<User> accounts = List.of(
                user(1, "Platform", "admin@innbucks.co.zw", null, User.Role.SUPER_ADMIN),
                user(27, "Tendai", "tmhlanga@innbucks.co.zw", null, User.Role.SUPER_ADMIN),
                user(9, "Rumbi", "rumbi@showtime.co.zw", "+263772999000", User.Role.EVENT_ORGANIZER));
        UserRepository users = mock(UserRepository.class);
        when(users.findAllExceptOnlyRole(eq(User.Role.CUSTOMER.name()))).thenReturn(accounts);
        TenantProfileRepository tenants = mock(TenantProfileRepository.class);
        when(tenants.findByUserIdIn(any())).thenReturn(List.of());

        staffProfiles = mock(StaffProfileRepository.class);
        StaffProfile tendai = StaffProfile.builder().userId(27L).contactPhone("+263771000027").build();
        when(staffProfiles.findAllByUserIdIn(any())).thenReturn(List.of(tendai));

        mvc = MockMvcBuilders.standaloneSetup(new AdminUserController(users, tenants, staffProfiles,
                        mock(UserAdminService.class), mock(MfaService.class)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static UsernamePasswordAuthenticationToken caller(String... authorities) {
        return new UsernamePasswordAuthenticationToken("caller@innbucks.co.zw", null,
                Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList());
    }

    @Test
    void aCallerWhoMayReadStaff_seesAStaffRowsContactPhone_andNothingElseGainsOne() throws Exception {
        mvc.perform(get("/admin/users").principal(caller(PermissionCatalog.USERS_READ, PermissionCatalog.STAFF_READ)))
                .andExpect(status().isOk())
                // the staff member: no sign-in phone, the contact number beside it
                .andExpect(jsonPath("$.data[?(@.id == 27)].contactPhone").value("+263771000027"))
                .andExpect(jsonPath("$.data[?(@.id == 27)].phoneNumber").doesNotExist())
                // the seeded owner: no staff profile, so no phone of either kind (a dash)
                .andExpect(jsonPath("$.data[?(@.id == 1)].phoneNumber").doesNotExist())
                .andExpect(jsonPath("$.data[?(@.id == 1)].contactPhone").doesNotExist())
                // a business account keeps its sign-in phone and gets no contact phone
                .andExpect(jsonPath("$.data[?(@.id == 9)].phoneNumber").value("+263772999000"))
                .andExpect(jsonPath("$.data[?(@.id == 9)].contactPhone").doesNotExist());
    }

    @Test
    void usersReadAlone_neverSeesAStaffContactPhone_andTheProfilesAreNotRead() throws Exception {
        mvc.perform(get("/admin/users").principal(caller(PermissionCatalog.USERS_READ)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id == 27)].email").value("tmhlanga@innbucks.co.zw"))
                .andExpect(jsonPath("$.data[?(@.id == 27)].contactPhone").doesNotExist());

        verify(staffProfiles, never()).findAllByUserIdIn(any());
    }

    @Test
    void noAuthenticationAtAll_seesNoContactPhone() throws Exception {
        mvc.perform(get("/admin/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id == 27)].contactPhone").doesNotExist());

        verify(staffProfiles, never()).findAllByUserIdIn(any());
    }
}
