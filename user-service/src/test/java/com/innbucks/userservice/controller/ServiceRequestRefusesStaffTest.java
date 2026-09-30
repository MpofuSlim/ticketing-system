package com.innbucks.userservice.controller;

import com.innbucks.userservice.dto.CreateServiceRequestDTO;
import com.innbucks.userservice.entity.ServiceRequest;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.exception.StaffPolicyException;
import com.innbucks.userservice.service.AuditEventType;
import com.innbucks.userservice.testsupport.StaffDispatchHarness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static com.innbucks.userservice.testsupport.StaffDispatchHarness.OWNER;
import static com.innbucks.userservice.testsupport.StaffDispatchHarness.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Service requests (V44): a staff account never requests or receives business
 * products — submit and approve are 409 {@code staff_account_not_eligible}. An
 * approval that adds a role now records {@code USER_ROLES_CHANGED}.
 */
class ServiceRequestRefusesStaffTest {

    private final StaffDispatchHarness h = new StaffDispatchHarness();

    private ServiceRequest pending(User requester) {
        ServiceRequest req = ServiceRequest.builder().id(14L).userId(requester.getId()).service("loyalty")
                .reason("Opening a shop").status(ServiceRequest.Status.PENDING)
                .createdAt(LocalDateTime.now(ZoneOffset.UTC)).build();
        when(h.serviceRequests.findById(14L)).thenReturn(Optional.of(req));
        when(h.serviceRequests.save(any(ServiceRequest.class))).thenAnswer(inv -> inv.getArgument(0));
        return req;
    }

    @Test
    @DisplayName("submit by a staff account: 409")
    void submit() {
        h.eligibleStaff("tariro.moyo@innbucks.co.zw", "CALL_CENTER_AGENT");
        CreateServiceRequestDTO dto = new CreateServiceRequestDTO();
        dto.setService("loyalty");
        dto.setReason("I also run a shop");
        assertThatThrownBy(() -> h.serviceRequestService.submit("tariro.moyo@innbucks.co.zw", dto))
                .isInstanceOf(StaffPolicyException.class)
                .extracting("errorCode").isEqualTo("staff_account_not_eligible");
    }

    @Test
    @DisplayName("approve for a staff requester (a request submitted before they became staff): 409, nothing granted")
    void approveStaff() throws Exception {
        User legacy = h.account("farai@innbucks.co.zw", "PRODUCT_OFFICER");
        ServiceRequest req = pending(legacy);
        h.mvc.perform(put("/admin/service-requests/14/approve").principal(as(OWNER)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.errorCode").value("staff_account_not_eligible"));
        assertThat(legacy.getRoles()).containsExactly("PRODUCT_OFFICER");
        assertThat(req.getStatus()).isEqualTo(ServiceRequest.Status.PENDING);
    }

    @Test
    @DisplayName("approve for a business: grants as before AND records USER_ROLES_CHANGED")
    void approveBusiness() throws Exception {
        User customer = h.account("rudo@example.com", "CUSTOMER");
        pending(customer);
        h.mvc.perform(put("/admin/service-requests/14/approve").principal(as(OWNER)))
                .andExpect(status().isOk());
        assertThat(customer.getRoles()).contains("MERCHANT_ADMIN");
        verify(h.audit).recordRequired(eq(AuditEventType.USER_ROLES_CHANGED), eq(OWNER), any(),
                eq(String.valueOf(customer.getId())), eq("USER"), any(), any());
        // The granted role's row is locked (FOR UPDATE) before it is read to
        // decide whether it is a staff role — §2.4's row-lock rule.
        var order = org.mockito.Mockito.inOrder(h.roles);
        order.verify(h.roles).lockAllByNameIn(eq(new java.util.TreeSet<>(java.util.Set.of("MERCHANT_ADMIN"))));
        order.verify(h.roles, org.mockito.Mockito.atLeastOnce()).findAllByNameIn(org.mockito.ArgumentMatchers.anyCollection());
    }
}
