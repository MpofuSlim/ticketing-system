package com.innbucks.userservice.service;

import com.innbucks.userservice.integration.LoyaltyServiceClient;
import com.innbucks.userservice.dto.CreateShopAdminDTO;
import com.innbucks.userservice.dto.CreateTeamMemberDTO;
import com.innbucks.userservice.dto.CustomerTier2RegisterDTO;
import com.innbucks.userservice.entity.User;
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
 * staff create already did; tier-2, shop-staff and team-member create are the
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

    @Test
    @DisplayName("team-member create: a letter-case variant of an existing address is a duplicate")
    void teamMember_caseVariant_isADuplicate() {
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
        when(users.existsByEmailIgnoreCase(VARIANT)).thenReturn(true);

        CreateTeamMemberDTO dto = new CreateTeamMemberDTO();
        dto.setFirstName("Grace");
        dto.setLastName("Clerkson");
        dto.setEmail(VARIANT);
        dto.setPhoneNumber("+263773456789");
        assertThatThrownBy(() -> service.createTeamMember(dto))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Email already registered");
        verify(users, never()).existsByEmail(anyString());
        verify(users, never()).save(any(User.class));
    }
}
