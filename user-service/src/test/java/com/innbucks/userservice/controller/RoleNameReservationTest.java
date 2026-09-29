package com.innbucks.userservice.controller;

import com.innbucks.userservice.testsupport.AdminDispatchHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;

import static com.innbucks.userservice.testsupport.AdminDispatchHarness.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code ADMIN} was never a platform role, and the call-center roles are built in
 * and spelled CALL_CENTER. A custom role must not take a name that reads as
 * either — someone would pick it from a role picker believing it was the real
 * thing.
 */
class RoleNameReservationTest {

    private static final String OWNER = "admin@innbucks.co.zw";

    private AdminDispatchHarness h;

    @BeforeEach
    void setUp() {
        h = new AdminDispatchHarness();
        h.account(1L, OWNER, "SUPER_ADMIN");
    }

    private org.springframework.test.web.servlet.ResultActions create(String name) throws Exception {
        return h.mvc.perform(post("/admin/roles").principal(as(OWNER))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\",\"description\":\"d\",\"permissions\":[\"users:read\"]}"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "admin", " Admin "})
    @DisplayName("ADMIN is reserved, however it is cased")
    void adminIsReserved(String name) throws Exception {
        create(name)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "The role name ADMIN is reserved: it has never been a platform role, so a role called "
                                + "that would read as authority it does not have. Use a built-in role "
                                + "(PRODUCT_OFFICER, PRODUCT_MANAGER, CALL_CENTER_AGENT, CALL_CENTER_SUPERVISOR, "
                                + "FRAUD_DESK) or choose another name."));
        assertThat(h.roleRows).doesNotContainKey("ADMIN");
    }

    @ParameterizedTest
    @ValueSource(strings = {"CALL_CENTER", "CALL_CENTRE", "CALL_CENTRE_AGENT", "call_centre_supervisor"})
    @DisplayName("a bare CALL_CENTER and every CALL_CENTRE spelling are reserved")
    void callCentreSpellingsAreReserved(String name) throws Exception {
        String normalized = name.trim().toUpperCase(java.util.Locale.ROOT);
        create(name)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "The role name " + normalized + " is reserved: the call-center roles are built in and "
                                + "spelled CALL_CENTER (CALL_CENTER_AGENT, CALL_CENTER_SUPERVISOR, with FRAUD_DESK "
                                + "as an add-on). Assign those, or choose another name."));
        assertThat(h.roleRows).doesNotContainKey(normalized);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CALL_CENTER_AGENT", "FRAUD_DESK"})
    @DisplayName("the built-in names themselves are taken by their rows (409)")
    void builtInNamesAreTaken(String name) throws Exception {
        create(name).andExpect(status().isConflict());
    }

    @ParameterizedTest
    @ValueSource(strings = {"CALL_CENTER_TEAM_LEAD", "ADMINISTRATOR_ASSISTANT", "REFUND_OFFICER"})
    @DisplayName("names that merely start alike are ordinary names")
    void similarNamesAreAllowed(String name) throws Exception {
        create(name).andExpect(status().isCreated());
    }
}
