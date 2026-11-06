package com.innbucks.userservice.service;

import com.innbucks.userservice.integration.LoyaltyServiceClient;
import com.innbucks.userservice.dto.CreateShopAdminDTO;
import com.innbucks.userservice.dto.CustomerTier2RegisterDTO;
import com.innbucks.userservice.entity.OrganizationMember;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.CustomerProfileRepository;
import com.innbucks.userservice.repository.DeviceRepository;
import com.innbucks.userservice.repository.OrganizationMemberRepository;
import com.innbucks.userservice.repository.OrganizationRepository;
import com.innbucks.userservice.repository.PendingRegistrationRepository;
import com.innbucks.userservice.repository.RoleRepository;
import com.innbucks.userservice.repository.StaffProfileRepository;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.security.AuthDetailsKeys;
import com.innbucks.userservice.security.NationalIdHasher;
import com.innbucks.userservice.testsupport.StaffFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * One account per email address, whatever its letter case (V49, owner decision
 * 2026-10-08): {@code GClerkson@…} and {@code gclerkson@…} never both exist.
 * V49's unique index on {@code UPPER(email)} enforces it in the database; every
 * writer also checks case-insensitively first, so the caller gets the ordinary
 * 400 "Email already registered" rather than a constraint error. Registration and
 * staff create already did; tier-2 and shop-staff create are the
 * writers this pins. Register's dispatch is pinned in {@code RegisterRolesFieldTest}.
 *
 * <p>The addresses are ordinary business ones: an InnBucks address is still
 * refused at all of these writers for its domain ({@code ReservedDomainAtEveryEmailWriterTest}).
 */
class EmailCaseUniquenessAtEveryWriterTest {

    private static final String HELD = "gclerkson@clerkson-traders.co.zw";
    private static final String VARIANT = "GClerkson@Clerkson-Traders.co.zw";

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

    private CustomerService customerService(CustomerProfileRepository profiles) {
        return new CustomerService(users, profiles, mock(DeviceRepository.class),
                mock(PendingRegistrationRepository.class), mock(PasswordEncoder.class), mock(OtpService.class),
                new NationalIdHasher("test-secret"), eligibility);
    }

    /** A tier-1 customer whose phone was verified a moment ago. */
    private User tier1Customer(CustomerProfileRepository profiles, long id, String phone) {
        User customer = User.builder().id(id).phoneNumber(phone)
                .roles(User.roleNames(User.Role.CUSTOMER)).active(true).build();
        when(users.findByPhoneNumber(phone)).thenReturn(Optional.of(customer));
        when(profiles.findByUserId(id)).thenReturn(Optional.of(com.innbucks.userservice.entity.CustomerProfile
                .builder().user(customer).registrationTier(1)
                .phoneVerifiedAt(LocalDateTime.now(ZoneOffset.UTC)).build()));
        return customer;
    }

    private static CustomerTier2RegisterDTO tier2(String phone, String email) {
        CustomerTier2RegisterDTO dto = new CustomerTier2RegisterDTO();
        dto.setMsisdn(phone);
        dto.setEmail(email);
        dto.setFirstName("Grace");
        dto.setLastName("Clerkson");
        dto.setNationalId("63-123456A-42");
        dto.setDateOfBirth(LocalDate.of(1990, 5, 17));
        CustomerTier2RegisterDTO.Address address = new CustomerTier2RegisterDTO.Address();
        address.setStreet1("123 Samora Machel Ave");
        address.setCity("Harare");
        address.setPostCode("0000");
        address.setCountry("ZW");
        dto.setAddress(address);
        return dto;
    }

    @Test
    @DisplayName("tier-2: a letter-case variant of another account's address is refused, nothing written")
    void tier2_caseVariantOfAnotherAccount_isRefused() {
        CustomerProfileRepository profiles = mock(CustomerProfileRepository.class);
        CustomerService service = customerService(profiles);
        User customer = tier1Customer(profiles, 42L, "+263770000042");
        User other = User.builder().id(99L).email(HELD).build();
        when(users.findAllByEmailIgnoreCase(VARIANT)).thenReturn(List.of(other));

        assertThatThrownBy(() -> service.registerTier2(tier2("+263770000042", VARIANT)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Email already registered");
        assertThat(customer.getEmail()).isNull();
        verify(users, never()).save(any(User.class));
    }

    @Test
    @DisplayName("tier-2: re-submitting the customer's OWN address in another case is not a clash")
    void tier2_ownAddressInAnotherCase_isAccepted() {
        CustomerProfileRepository profiles = mock(CustomerProfileRepository.class);
        CustomerService service = customerService(profiles);
        User customer = tier1Customer(profiles, 42L, "+263770000042");
        customer.setEmail(HELD);
        when(users.findAllByEmailIgnoreCase(VARIANT)).thenReturn(List.of(customer));

        service.registerTier2(tier2("+263770000042", VARIANT));

        verify(users).save(customer);
    }

    @Test
    @DisplayName("tier-2: an address nobody holds is stored")
    void tier2_freeAddress_isStored() {
        CustomerProfileRepository profiles = mock(CustomerProfileRepository.class);
        CustomerService service = customerService(profiles);
        User customer = tier1Customer(profiles, 42L, "+263770000042");
        when(users.findAllByEmailIgnoreCase(HELD)).thenReturn(List.of());

        service.registerTier2(tier2("+263770000042", HELD));

        assertThat(customer.getEmail()).isEqualTo(HELD);
        verify(users).save(customer);
    }

    @Test
    @DisplayName("shop-staff create: a letter-case variant of an existing address is a duplicate")
    void shopStaff_caseVariant_isADuplicate() {
        LoyaltyServiceClient loyalty = mock(LoyaltyServiceClient.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ShopStaffService> self = mock(ObjectProvider.class);
        OrganizationMemberRepository members = mock(OrganizationMemberRepository.class);
        ShopStaffService service = new ShopStaffService(users, mock(PasswordEncoder.class), loyalty,
                mock(ApplicationEventPublisher.class), mock(jakarta.validation.Validator.class), self,
                members, eligibility);
        ReflectionTestUtils.setField(service, "deploymentCountry", "ZW");
        UUID shopId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        // A merchant admin's scope is its session organization's merchants.
        UUID organizationId = UUID.randomUUID();
        User merchantAdmin = User.builder().id(1L).email("merchant@shop.co.zw")
                .roles(User.roleNames(User.Role.MERCHANT_ADMIN)).build();
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(merchantAdmin.getEmail(), null);
        auth.setDetails(new java.util.HashMap<>(java.util.Map.of(AuthDetailsKeys.ORGANIZATION_ID, organizationId)));
        SecurityContextHolder.getContext().setAuthentication(auth);
        when(users.findByEmail(merchantAdmin.getEmail())).thenReturn(Optional.of(merchantAdmin));
        when(members.findByOrganizationIdAndUserId(organizationId, merchantAdmin.getId()))
                .thenReturn(Optional.of(OrganizationMember.builder().organizationId(organizationId)
                        .userId(merchantAdmin.getId()).role(OrganizationMember.Role.OWNER).build()));
        when(loyalty.merchantIdsForOrganization(organizationId)).thenReturn(List.of(merchantId));
        when(loyalty.findShop(shopId)).thenReturn(Optional.of(new LoyaltyServiceClient.ShopLookupResponse(
                shopId.toString(), merchantId.toString(), "tenant-1", "ACTIVE")));
        when(users.existsByEmailIgnoreCase(VARIANT)).thenReturn(true);

        CreateShopAdminDTO dto = new CreateShopAdminDTO();
        dto.setFirstName("Grace");
        dto.setLastName("Clerkson");
        dto.setEmail(VARIANT);
        dto.setPhoneNumber("+263771234567");
        dto.setShopId(shopId);
        assertThatThrownBy(() -> service.createShopAdmin(dto))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Email already registered");
        verify(users, never()).existsByEmail(anyString());
        verify(users, never()).save(any(User.class));
    }

    // ------------------------------------------------------------------
    // Sales-agent invite accept (V56)
    // ------------------------------------------------------------------

    private com.innbucks.userservice.repository.SalesAgentInviteRepository invites;
    private OtpService otp;

    /** The accept, with a live invite to {@code inviteEmail} for {@code phone}. */
    private SalesAgentInviteService salesAgentInvites(String token, String phone, String inviteEmail) {
        invites = mock(com.innbucks.userservice.repository.SalesAgentInviteRepository.class);
        otp = mock(OtpService.class);
        when(otp.verifySalesAgentInviteCode(phone, "482913")).thenReturn(true);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        when(invites.findByTokenHash(com.innbucks.userservice.security.SalesAgentInviteTokens.hash(token)))
                .thenReturn(Optional.of(com.innbucks.userservice.entity.SalesAgentInvite.builder().id(1L)
                        .teamMemberId(UUID.randomUUID()).organizerUuid(UUID.randomUUID())
                        .phoneNumber(phone).email(inviteEmail).createdAt(now).expiresAt(now.plusHours(1)).build()));
        when(invites.consume(anyString(), any(), any())).thenReturn(1);
        com.innbucks.userservice.config.StaffProvisioningCheck provisioning =
                mock(com.innbucks.userservice.config.StaffProvisioningCheck.class);
        com.innbucks.userservice.config.SalesAgentInviteProperties properties =
                new com.innbucks.userservice.config.SalesAgentInviteProperties();
        properties.afterPropertiesSet();
        return new SalesAgentInviteService(invites, users, mock(CustomerProfileRepository.class),
                mock(com.innbucks.userservice.repository.RefreshTokenRepository.class), eligibility, otp,
                mock(PasswordEncoder.class), mock(TokenVersionBumper.class), mock(AuditService.class),
                mock(ApplicationEventPublisher.class), properties, provisioning,
                mock(com.innbucks.userservice.cells.CellAffinityChecker.class));
    }

    @Test
    @DisplayName("sales-agent accept: an invite email another account holds in another case is email_in_use")
    void salesAgentAccept_caseVariantOfAnotherAccount_isRefused() {
        String token = com.innbucks.userservice.security.SalesAgentInviteTokens.generate();
        SalesAgentInviteService service = salesAgentInvites(token, "+263770000042", VARIANT);
        User app = User.builder().id(42L).userUuid(UUID.randomUUID()).phoneNumber("+263770000042")
                .roles(User.roleNames(User.Role.CUSTOMER)).active(true).approved(true).password("x").build();
        when(users.findByPhoneNumber("+263770000042")).thenReturn(Optional.of(app));
        when(users.lockById(42L)).thenReturn(Optional.of(app));
        when(users.findById(42L)).thenReturn(Optional.of(app));
        when(users.findAllByEmailIgnoreCase(VARIANT)).thenReturn(List.of(User.builder().id(99L).email(HELD).build()));

        assertThatThrownBy(() -> service.accept(new com.innbucks.userservice.dto.SalesAgentInviteDTOs.AcceptRequest(
                token, "482913", "Correct-Horse-7-Battery"), AuditContext.none()))
                .isInstanceOf(com.innbucks.userservice.exception.SalesAgentInviteException.class)
                .extracting("errorCode").isEqualTo("email_in_use");
        assertThat(app.getEmail()).isNull();
        verify(users, never()).existsByEmail(anyString());
        verify(users, never()).saveAndFlush(any(User.class));
        // Refused after the code: only the phone's holder learns about the email.
        verify(otp).verifySalesAgentInviteCode("+263770000042", "482913");
    }

    @Test
    @DisplayName("sales-agent accept: an account whose OWN address differs only in case keeps it, no clash")
    void salesAgentAccept_ownAddressInAnotherCase_isAccepted() {
        String token = com.innbucks.userservice.security.SalesAgentInviteTokens.generate();
        SalesAgentInviteService service = salesAgentInvites(token, "+263770000042", HELD);
        User organizer = User.builder().id(42L).userUuid(UUID.randomUUID()).phoneNumber("+263770000042")
                .email(VARIANT).roles(User.roleNames(User.Role.EVENT_ORGANIZER, User.Role.CUSTOMER))
                .active(true).approved(true).password("x").build();
        when(users.findByPhoneNumber("+263770000042")).thenReturn(Optional.of(organizer));
        when(users.lockById(42L)).thenReturn(Optional.of(organizer));
        when(users.findById(42L)).thenReturn(Optional.of(organizer));

        service.accept(new com.innbucks.userservice.dto.SalesAgentInviteDTOs.AcceptRequest(
                token, "482913", "Correct-Horse-7-Battery"), AuditContext.none());

        assertThat(organizer.getEmail()).isEqualTo(VARIANT);
        assertThat(organizer.getRoles()).contains("SALES_AGENT");
        verify(users).saveAndFlush(organizer);
    }
}
