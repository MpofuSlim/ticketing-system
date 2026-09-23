package com.innbucks.userservice.service;

import com.innbucks.userservice.dto.CreateServiceRequestDTO;
import com.innbucks.userservice.entity.ServiceRequest;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.ServiceRequestRepository;
import com.innbucks.userservice.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * A product request is made FOR an organization (V39): stamped at submission,
 * and granted to that organization at approval — in addition to the bundle the
 * person has always received, which stays exactly as it was.
 */
class ServiceRequestOrganizationTest {

    private final ServiceRequestRepository requests = mock(ServiceRequestRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final OrganizationService organizations = mock(OrganizationService.class);
    private ServiceRequestService service;

    private final UUID orgId = UUID.randomUUID();
    private User requester;
    private User reviewer;

    @BeforeEach
    void setUp() {
        requester = User.builder().id(42L).email("alice@rudo.co.zw").phoneNumber("+263771234567")
                .firstName("Alice").lastName("Moyo")
                .roles(new LinkedHashSet<>(List.of(User.Role.EVENT_ORGANIZER.name())))
                .defaultServices(new LinkedHashSet<>(List.of("ticketing")))
                .build();
        reviewer = User.builder().id(99L).email("admin@innbucks.co.zw").firstName("S").lastName("A")
                .roles(new LinkedHashSet<>(List.of(User.Role.SUPER_ADMIN.name())))
                .defaultServices(new LinkedHashSet<>())
                .build();
        when(users.findByEmail("alice@rudo.co.zw")).thenReturn(Optional.of(requester));
        when(users.findByEmail("admin@innbucks.co.zw")).thenReturn(Optional.of(reviewer));
        when(users.findById(42L)).thenReturn(Optional.of(requester));
        when(users.save(any(User.class))).thenAnswer(i -> i.getArgument(0));
        when(requests.save(any(ServiceRequest.class))).thenAnswer(i -> i.getArgument(0));
        when(requests.findByUserIdAndServiceAndStatus(any(), any(), any())).thenReturn(Optional.empty());

        service = new ServiceRequestService(requests, users, mock(ApplicationEventPublisher.class));
        ReflectionTestUtils.setField(service, "organizationService", organizations);
    }

    @Test
    @DisplayName("a request is stamped with the organization it is for")
    void submitStampsOrganization() {
        UUID sessionOrg = UUID.randomUUID();
        when(organizations.organizationForRequest(requester, sessionOrg)).thenReturn(orgId);
        CreateServiceRequestDTO dto = new CreateServiceRequestDTO();
        dto.setService("marketplace");
        dto.setReason("We want to sell tickets and merchandise.");

        service.submit("alice@rudo.co.zw", sessionOrg, dto);

        ArgumentCaptor<ServiceRequest> saved = ArgumentCaptor.forClass(ServiceRequest.class);
        verify(requests).save(saved.capture());
        assertThat(saved.getValue().getOrganizationId()).isEqualTo(orgId);
    }

    @Test
    @DisplayName("approval grants the organization the product, and still grants the person their bundle")
    void approveGrantsOrganizationAndPerson() {
        ServiceRequest pending = ServiceRequest.builder().id(14L).userId(42L).service("marketplace")
                .reason("Sell online").status(ServiceRequest.Status.PENDING).organizationId(orgId)
                .createdAt(LocalDateTime.now(ZoneOffset.UTC)).build();
        when(requests.findById(14L)).thenReturn(Optional.of(pending));

        service.approve(14L, "admin@innbucks.co.zw");

        verify(organizations).grantProduct(requester, orgId, "marketplace", reviewer);
        assertThat(requester.getDefaultServices()).contains("marketplace");
        assertThat(requester.getRoles()).contains(User.Role.MERCHANT_ADMIN.name());
    }

    @Test
    @DisplayName("without an organization service the approval behaves exactly as before V39")
    void approveWithoutOrganizationsUnchanged() {
        ReflectionTestUtils.setField(service, "organizationService", null);
        ServiceRequest pending = ServiceRequest.builder().id(14L).userId(42L).service("marketplace")
                .reason("Sell online").status(ServiceRequest.Status.PENDING)
                .createdAt(LocalDateTime.now(ZoneOffset.UTC)).build();
        when(requests.findById(14L)).thenReturn(Optional.of(pending));

        service.approve(14L, "admin@innbucks.co.zw");

        assertThat(requester.getDefaultServices()).contains("marketplace");
        verifyNoInteractions(organizations);
    }
}
