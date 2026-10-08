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
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.innbucks.userservice.testsupport.StaffDispatchHarness.OWNER;
import static com.innbucks.userservice.testsupport.StaffDispatchHarness.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * An InnBucks address is an ordinary business and customer address (owner
 * decision, 2026-10-08, reversing V44's reservation): register, tier-2,
 * shop-staff create, team-member create and the first approval of a
 * registration all accept {@code @innbucks.co.zw} / {@code @innbucks.co.ke}
 * like any other domain. Such an account gains no staff authority — that still
 * needs an invite-proven email and an accepted staff profile.
 *
 * <p>What each writer refuses instead is a letter-case variant of an address
 * another account already holds: {@code GClerkson@…} and {@code gclerkson@…}
 * never both exist (V49 backs this with a unique index on {@code UPPER(email)}).
 * Register's dispatch is pinned in {@code RegisterRolesFieldTest}; these drive
 * the real services with the real {@link StaffEligibility}.
 */
class InnBucksAddressAtEveryEmailWriterTest {

    private static final String INNBUCKS_EMAIL = "gclerkson@innbucks.co.zw";

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
    @DisplayName("tier-2: an InnBucks address is stored like any other")
    void tier2_innbucksAddress_isStored() {
        CustomerProfileRepository profiles = mock(CustomerProfileRepository.class);
        CustomerService service = customerService(profiles);
        User customer = tier1Customer(profiles, 42L, "+263770000042");
        when(users.findAllByEmailIgnoreCase(INNBUCKS_EMAIL)).thenReturn(List.of());

        service.registerTier2(tier2("+263770000042", INNBUCKS_EMAIL));

        assertThat(customer.getEmail()).isEqualTo(INNBUCKS_EMAIL);
        verify(users).save(customer);
    }

    @Test
    @DisplayName("tier-2: a letter-case variant of another account's address is refused, nothing written")
    void tier2_caseVariantOfAnotherAccount_isRefused() {
        CustomerProfileRepository profiles = mock(CustomerProfileRepository.class);
        CustomerService service = customerService(profiles);
        User customer = tier1Customer(profiles, 42L, "+263770000042");
        User other = User.builder().id(99L).email(INNBUCKS_EMAIL).build();
        when(users.findAllByEmailIgnoreCase("GClerkson@innbucks.co.zw")).thenReturn(List.of(other));

        assertThatThrownBy(() -> service.registerTier2(tier2("+263770000042", "GClerkson@innbucks.co.zw")))
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
        customer.setEmail(INNBUCKS_EMAIL);
        when(users.findAllByEmailIgnoreCase("GClerkson@innbucks.co.zw")).thenReturn(List.of(customer));

        service.registerTier2(tier2("+263770000042", "GClerkson@innbucks.co.zw"));

        verify(users).save(customer);
    }

    @Test
    @DisplayName("tier-2 for a phone whose account is staff, with an off-domain email: 409 in tier-2's own words")
    void tier2_staffAccount_offDomainEmail() {
        CustomerProfileRepository profiles = mock(CustomerProfileRepository.class);
        CustomerService service = customerService(profiles);
        // A legacy staff account that is ALSO a customer (tier-2 only serves customer rows).
        User legacy = User.builder().id(42L).email("farai@innbucks.co.zw").phoneNumber("+263770000042")
                .roles(User.roleNames(User.Role.PRODUCT_OFFICER, User.Role.CUSTOMER)).active(true).build();
        when(users.findByPhoneNumber("+263770000042")).thenReturn(Optional.of(legacy));
        when(profiles.findByUserId(42L)).thenReturn(Optional.of(com.innbucks.userservice.entity.CustomerProfile
                .builder().user(legacy).registrationTier(1)
                .phoneVerifiedAt(LocalDateTime.now(ZoneOffset.UTC)).build()));
        CustomerTier2RegisterDTO dto = new CustomerTier2RegisterDTO();
        dto.setMsisdn("+263770000042");
        dto.setEmail("farai.personal@gmail.com");
        assertThatThrownBy(() -> service.registerTier2(dto))
                .isInstanceOf(StaffPolicyException.class)
                .hasMessage("This number belongs to an InnBucks staff account, which can't be registered as a customer.")
                .extracting("errorCode").isEqualTo("staff_account_not_eligible");
        assertThat(legacy.getEmail()).isEqualTo("farai@innbucks.co.zw");
    }

    private ShopStaffService shopStaffService(LoyaltyServiceClient loyalty, UUID shopId, UUID merchantId) {
        @SuppressWarnings("unchecked")
        ObjectProvider<ShopStaffService> self = mock(ObjectProvider.class);
        ShopStaffService service = new ShopStaffService(users, mock(PasswordEncoder.class), loyalty,
                mock(ApplicationEventPublisher.class), mock(jakarta.validation.Validator.class), self,
                mock(OrganizationMemberRepository.class));
        ReflectionTestUtils.setField(service, "deploymentCountry", "ZW");
        User merchantAdmin = User.builder().id(1L).email("merchant@shop.co.zw")
                .roles(User.roleNames(User.Role.MERCHANT_ADMIN)).loyaltyMerchantId(merchantId).build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(merchantAdmin.getEmail(), null));
        when(users.findByEmail(merchantAdmin.getEmail())).thenReturn(Optional.of(merchantAdmin));
        when(loyalty.findShop(shopId)).thenReturn(Optional.of(new LoyaltyServiceClient.ShopLookupResponse(
                shopId.toString(), merchantId.toString(), "tenant-1", "ACTIVE")));
        return service;
    }

    private static CreateShopAdminDTO shopAdmin(String email, UUID shopId) {
        CreateShopAdminDTO dto = new CreateShopAdminDTO();
        dto.setFirstName("Grace");
        dto.setLastName("Clerkson");
        dto.setEmail(email);
        dto.setPhoneNumber("+263771234567");
        dto.setShopId(shopId);
        return dto;
    }

    @Test
    @DisplayName("shop-staff create: a letter-case variant of an existing address is a duplicate (case-insensitive check)")
    void shopStaff_caseVariant_isADuplicate() {
        UUID shopId = UUID.randomUUID();
        ShopStaffService service = shopStaffService(mock(LoyaltyServiceClient.class), shopId, UUID.randomUUID());
        when(users.existsByEmailIgnoreCase("GClerkson@innbucks.co.zw")).thenReturn(true);

        assertThatThrownBy(() -> service.createShopAdmin(shopAdmin("GClerkson@innbucks.co.zw", shopId)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Email already registered");
        verify(users, never()).existsByEmail(anyString());
        verify(users, never()).save(any(User.class));
    }

    @Test
    @DisplayName("shop-staff create: an InnBucks address is not refused for its domain")
    void shopStaff_innbucksAddress_isNotRefusedForItsDomain() {
        UUID shopId = UUID.randomUUID();
        ShopStaffService service = shopStaffService(mock(LoyaltyServiceClient.class), shopId, UUID.randomUUID());
        // Reaching the phone-uniqueness check proves the email passed every
        // email check, the domain included.
        when(users.existsByPhoneNumberAndHomeCountry(anyString(), anyString())).thenReturn(true);

        assertThatThrownBy(() -> service.createShopAdmin(shopAdmin(INNBUCKS_EMAIL, shopId)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Phone number already registered");
        verify(users).existsByEmailIgnoreCase(INNBUCKS_EMAIL);
    }

    @Test
    @DisplayName("team-member create: InnBucks address accepted past the email checks; a case variant is a duplicate")
    void teamMember() {
        TeamMemberService service = new TeamMemberService(users, mock(TeamMemberEventAssignmentRepository.class),
                mock(PasswordEncoder.class), mock(ApplicationEventPublisher.class), mock(AccountSessionRevoker.class));
        ReflectionTestUtils.setField(service, "deploymentCountry", "ZW");
        ReflectionTestUtils.setField(service, "bootstrapAdminEmail",
                com.innbucks.userservice.util.BootstrapAdminEmail.DEFAULT_ADDRESS);
        User organizer = User.builder().id(7L).userUuid(UUID.randomUUID()).email("olive@harare-arena.co.zw")
                .roles(User.roleNames(User.Role.EVENT_ORGANIZER)).active(true).build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(organizer.getEmail(), null));
        when(users.findByEmail(organizer.getEmail())).thenReturn(Optional.of(organizer));

        CreateTeamMemberDTO dto = new CreateTeamMemberDTO();
        dto.setFirstName("Grace");
        dto.setLastName("Clerkson");
        dto.setEmail("GClerkson@innbucks.co.zw");
        dto.setPhoneNumber("+263773456789");
        when(users.existsByEmailIgnoreCase("GClerkson@innbucks.co.zw")).thenReturn(true);
        assertThatThrownBy(() -> service.createTeamMember(dto))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Email already registered");
        verify(users, never()).existsByEmail(anyString());
        verify(users, never()).save(any(User.class));
    }

    @Test
    @DisplayName("first approval of a registration at an InnBucks address: approved like any other")
    void firstApproval() throws Exception {
        StaffDispatchHarness h = new StaffDispatchHarness();
        User merchant = h.account(INNBUCKS_EMAIL, "MERCHANT_ADMIN");
        merchant.setActive(false);
        merchant.setApproved(false);
        h.mvc.perform(put("/admin/users/{id}/active", merchant.getId()).principal(as(OWNER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\":true}"))
                .andExpect(status().isOk());
        assertThat(merchant.isApproved()).isTrue();
        assertThat(merchant.isActive()).isTrue();
    }
}
