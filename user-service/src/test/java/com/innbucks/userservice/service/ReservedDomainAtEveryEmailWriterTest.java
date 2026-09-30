package com.innbucks.userservice.service;

import com.innbucks.userservice.integration.LoyaltyServiceClient;
import com.innbucks.userservice.dto.CreateShopAdminDTO;
import com.innbucks.userservice.dto.CreateTeamMemberDTO;
import com.innbucks.userservice.dto.CustomerTier2RegisterDTO;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.exception.StaffPolicyException;
import com.innbucks.userservice.repository.CustomerProfileRepository;
import com.innbucks.userservice.repository.DeviceRepository;
import com.innbucks.userservice.repository.OrganizationMemberRepository;
import com.innbucks.userservice.repository.OrganizationRepository;
import com.innbucks.userservice.repository.PendingRegistrationRepository;
import com.innbucks.userservice.repository.RoleRepository;
import com.innbucks.userservice.repository.StaffProfileRepository;
import com.innbucks.userservice.repository.TeamMemberEventAssignmentRepository;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.security.NationalIdHasher;
import com.innbucks.userservice.testsupport.StaffDispatchHarness;
import com.innbucks.userservice.testsupport.StaffFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static com.innbucks.userservice.testsupport.StaffDispatchHarness.OWNER;
import static com.innbucks.userservice.testsupport.StaffDispatchHarness.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A staff address is never written by any path but {@code POST /admin/staff}
 * (V44 §2.4): register, tier-2, shop-staff create, team-member create and the
 * FIRST approval of a registration all refuse it with 400
 * {@code email_domain_reserved}, and each does so BEFORE its duplicate-email
 * check, so none of them tells a caller which staff addresses exist. Register's
 * dispatch is pinned in {@code RegisterRolesFieldTest}; these drive the real
 * services with the real {@link StaffEligibility}.
 */
class ReservedDomainAtEveryEmailWriterTest {

    private static final String STAFF_EMAIL = "tariro.moyo@innbucks.co.zw";

    private UserRepository users;
    private StaffEligibility eligibility;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        RoleRepository roles = mock(RoleRepository.class);
        eligibility = StaffFixtures.eligibility(mock(StaffProfileRepository.class), new RoleGrantGuard(users, roles),
                mock(OrganizationMemberRepository.class), mock(OrganizationRepository.class), roles, users);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void assertReserved(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(StaffPolicyException.class)
                .hasMessage("InnBucks staff addresses can't be used here. Your administrator will invite you.")
                .extracting("errorCode").isEqualTo("email_domain_reserved");
    }

    @Test
    @DisplayName("tier-2: refused before the profile is even loaded")
    void tier2() {
        CustomerProfileRepository profiles = mock(CustomerProfileRepository.class);
        CustomerService service = new CustomerService(users, profiles, mock(DeviceRepository.class),
                mock(PendingRegistrationRepository.class), mock(PasswordEncoder.class), mock(OtpService.class),
                new NationalIdHasher("test-secret"), eligibility);
        CustomerTier2RegisterDTO dto = new CustomerTier2RegisterDTO();
        dto.setMsisdn("+263770000001");
        dto.setEmail(STAFF_EMAIL);
        assertReserved(() -> service.registerTier2(dto));
        // The platform admin's own address answers exactly like any other staff
        // address — the reserved check runs BEFORE the bootstrap-admin check.
        ReflectionTestUtils.setField(service, "bootstrapAdminEmail",
                com.innbucks.userservice.util.BootstrapAdminEmail.DEFAULT_ADDRESS);
        dto.setEmail(com.innbucks.userservice.util.BootstrapAdminEmail.DEFAULT_ADDRESS);
        assertReserved(() -> service.registerTier2(dto));
        verifyNoInteractions(profiles);
        verify(users, never()).findByPhoneNumber(anyString());
    }

    @Test
    @DisplayName("tier-2 for a phone whose account is staff, with an off-domain email: 409 in tier-2's own words")
    void tier2_staffAccount_offDomainEmail() {
        CustomerProfileRepository profiles = mock(CustomerProfileRepository.class);
        CustomerService service = new CustomerService(users, profiles, mock(DeviceRepository.class),
                mock(PendingRegistrationRepository.class), mock(PasswordEncoder.class), mock(OtpService.class),
                new NationalIdHasher("test-secret"), eligibility);
        // A legacy staff account that is ALSO a customer (tier-2 only serves customer rows).
        User legacy = User.builder().id(42L).email("farai@innbucks.co.zw").phoneNumber("+263770000042")
                .roles(User.roleNames(User.Role.PRODUCT_OFFICER, User.Role.CUSTOMER)).active(true).build();
        when(users.findByPhoneNumber("+263770000042")).thenReturn(Optional.of(legacy));
        when(profiles.findByUserId(42L)).thenReturn(Optional.of(com.innbucks.userservice.entity.CustomerProfile
                .builder().user(legacy).registrationTier(1)
                .phoneVerifiedAt(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)).build()));
        CustomerTier2RegisterDTO dto = new CustomerTier2RegisterDTO();
        dto.setMsisdn("+263770000042");
        dto.setEmail("farai.personal@gmail.com");
        assertThatThrownBy(() -> service.registerTier2(dto))
                .isInstanceOf(StaffPolicyException.class)
                .hasMessage("This number belongs to an InnBucks staff account, which can't be registered as a customer.")
                .extracting("errorCode").isEqualTo("staff_account_not_eligible");
        assertThat(legacy.getEmail()).isEqualTo("farai@innbucks.co.zw");
    }

    @Test
    @DisplayName("shop-staff create: refused before the duplicate check")
    void shopStaff() {
        LoyaltyServiceClient loyalty = mock(LoyaltyServiceClient.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ShopStaffService> self = mock(ObjectProvider.class);
        ShopStaffService service = new ShopStaffService(users, mock(PasswordEncoder.class), loyalty,
                mock(ApplicationEventPublisher.class), mock(jakarta.validation.Validator.class), self,
                mock(OrganizationMemberRepository.class), eligibility);
        ReflectionTestUtils.setField(service, "deploymentCountry", "ZW");
        UUID shopId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        User merchantAdmin = User.builder().id(1L).email("merchant@shop.co.zw")
                .roles(User.roleNames(User.Role.MERCHANT_ADMIN)).loyaltyMerchantId(merchantId).build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(merchantAdmin.getEmail(), null));
        when(users.findByEmail(merchantAdmin.getEmail())).thenReturn(Optional.of(merchantAdmin));
        when(loyalty.findShop(shopId)).thenReturn(Optional.of(new LoyaltyServiceClient.ShopLookupResponse(
                shopId.toString(), merchantId.toString(), "tenant-1", "ACTIVE")));
        when(users.existsByEmail(anyString())).thenReturn(true);

        CreateShopAdminDTO dto = new CreateShopAdminDTO();
        dto.setFirstName("Tariro");
        dto.setLastName("Moyo");
        dto.setEmail(STAFF_EMAIL);
        dto.setPhoneNumber("+263771234567");
        dto.setShopId(shopId);
        assertReserved(() -> service.createShopAdmin(dto));
        ReflectionTestUtils.setField(service, "bootstrapAdminEmail",
                com.innbucks.userservice.util.BootstrapAdminEmail.DEFAULT_ADDRESS);
        dto.setEmail(com.innbucks.userservice.util.BootstrapAdminEmail.DEFAULT_ADDRESS);
        assertReserved(() -> service.createShopAdmin(dto));
        verify(users, never()).existsByEmail(anyString());
        verify(users, never()).save(any(User.class));
    }

    @Test
    @DisplayName("team-member create: refused before the duplicate check")
    void teamMember() {
        TeamMemberService service = new TeamMemberService(users, mock(TeamMemberEventAssignmentRepository.class),
                mock(PasswordEncoder.class), mock(ApplicationEventPublisher.class), mock(AccountSessionRevoker.class),
                eligibility);
        ReflectionTestUtils.setField(service, "deploymentCountry", "ZW");
        ReflectionTestUtils.setField(service, "bootstrapAdminEmail",
                com.innbucks.userservice.util.BootstrapAdminEmail.DEFAULT_ADDRESS);
        User organizer = User.builder().id(7L).userUuid(UUID.randomUUID()).email("olive@harare-arena.co.zw")
                .roles(User.roleNames(User.Role.EVENT_ORGANIZER)).active(true).build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(organizer.getEmail(), null));
        when(users.findByEmail(organizer.getEmail())).thenReturn(Optional.of(organizer));
        when(users.existsByEmail(anyString())).thenReturn(true);

        CreateTeamMemberDTO dto = new CreateTeamMemberDTO();
        dto.setFirstName("Tariro");
        dto.setLastName("Moyo");
        dto.setEmail("Tariro.Moyo@InnBucks.co.ke");
        dto.setPhoneNumber("+263773456789");
        assertReserved(() -> service.createTeamMember(dto));
        dto.setEmail(com.innbucks.userservice.util.BootstrapAdminEmail.DEFAULT_ADDRESS);
        assertReserved(() -> service.createTeamMember(dto));
        verify(users, never()).existsByEmail(anyString());
        verify(users, never()).save(any(User.class));
    }

    @Test
    @DisplayName("first approval of a registration at a staff address: 400, nothing approved or mailed")
    void firstApproval() throws Exception {
        StaffDispatchHarness h = new StaffDispatchHarness();
        User squatter = h.account(STAFF_EMAIL, "MERCHANT_ADMIN");
        squatter.setActive(false);
        squatter.setApproved(false);
        h.mvc.perform(put("/admin/users/{id}/active", squatter.getId()).principal(as(OWNER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.errorCode").value("email_domain_reserved"));
        assertThat(squatter.isActive()).isFalse();
        assertThat(squatter.isApproved()).isFalse();
        verify(h.eventPublisher, never()).publishEvent(any(
                com.innbucks.userservice.event.CredentialDeliveryRequested.class));
        verify(h.audit).recordFailure(eq(AuditEventType.STAFF_GRANT_REFUSED), eq(OWNER), any(),
                any(), any(), eq("email_domain_reserved"), any(), any());

        // A business address is approved exactly as before.
        User merchant = h.account("rudo@chikwanha-traders.co.zw", "MERCHANT_ADMIN");
        merchant.setActive(false);
        merchant.setApproved(false);
        h.mvc.perform(put("/admin/users/{id}/active", merchant.getId()).principal(as(OWNER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\":true}"))
                .andExpect(status().isOk());
        assertThat(merchant.isApproved()).isTrue();
    }
}
