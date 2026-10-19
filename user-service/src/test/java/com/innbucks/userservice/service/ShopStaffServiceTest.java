package com.innbucks.userservice.service;

import com.innbucks.userservice.dto.CreateShopAdminDTO;
import com.innbucks.userservice.dto.CreateShopUserDTO;
import com.innbucks.userservice.dto.UserResponseDTO;
import com.innbucks.userservice.entity.OrganizationMember;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.event.CredentialDeliveryRequested;
import com.innbucks.userservice.integration.LoyaltyServiceClient;
import com.innbucks.userservice.repository.OrganizationMemberRepository;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.security.AuthDetailsKeys;
import com.innbucks.userservice.testsupport.BuiltInRoleRows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ShopStaffService}'s onboarding-notification wiring.
 *
 * <p>Pins the behaviour added when the email channel was wired in: a newly
 * created SHOP_ADMIN / SHOP_USER is emailed their credentials (email is the
 * primary channel — staff always onboard with an address), SMS is the
 * fallback when the email gateway rejects the message, and a delivery failure
 * on BOTH channels must never roll back or fail the creation (best-effort,
 * mirroring {@code UserAdminService#notifyApproval}).
 *
 * <p>Pure Mockito, no Spring context — the {@code @Value} deployment-country
 * field is set via reflection since it isn't a constructor dependency.
 */
@ExtendWith(MockitoExtension.class)
class ShopStaffServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private LoyaltyServiceClient loyaltyServiceClient;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private OrganizationMemberRepository organizationMembers;
    @Mock private StaffEligibility staffEligibility;

    @InjectMocks private ShopStaffService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "deploymentCountry", "ZW");
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(User caller) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(caller.getEmail(), null, authoritiesOf(caller)));
        when(userRepository.findByEmail(caller.getEmail())).thenReturn(Optional.of(caller));
    }

    /** The permission authorities a fresh token for {@code caller}'s built-in
     *  roles carries, as JwtFilter grants them (bare codes, from V35's grants as
     *  mirrored by {@link BuiltInRoleRows}). listForShop reads them to decide
     *  merchant-wide vs own-shop, so a test token must carry them. */
    private static List<org.springframework.security.core.authority.SimpleGrantedAuthority> authoritiesOf(User caller) {
        return caller.getRoles().stream()
                .flatMap(r -> BuiltInRoleRows.GRANTS.getOrDefault(r, java.util.Set.of()).stream())
                .distinct()
                .map(org.springframework.security.core.authority.SimpleGrantedAuthority::new)
                .toList();
    }

    /**
     * A merchant admin as production has them: NO stamped loyaltyMerchantId,
     * authority = the merchants its session organization ({@link #ORG}) owns,
     * which it runs as OWNER. Use it only where the merchant set is actually
     * resolved (strict stubs).
     */
    private User asOrgMerchantAdmin(UUID... ownedMerchants) {
        User admin = merchantAdminNoBinding("merchant@x.com");
        authenticateInOrganization(admin, OrganizationMember.Role.OWNER);
        when(loyaltyServiceClient.merchantIdsForOrganization(ORG)).thenReturn(List.of(ownedMerchants));
        return admin;
    }

    private CreateShopAdminDTO shopAdminDto(UUID shopId) {
        CreateShopAdminDTO dto = new CreateShopAdminDTO();
        dto.setFirstName("Tendai");
        dto.setLastName("Moyo");
        dto.setEmail("tendai@shop.co.zw");
        dto.setPhoneNumber("+263771234567");
        dto.setShopId(shopId);
        return dto;
    }

    private void stubShopAdminHappyPath(UUID shopId, UUID merchantId) {
        when(loyaltyServiceClient.findShop(shopId)).thenReturn(Optional.of(
                new LoyaltyServiceClient.ShopLookupResponse(
                        shopId.toString(), merchantId.toString(), "tenant-1", "ACTIVE")));
        when(userRepository.existsByEmailIgnoreCase("tendai@shop.co.zw")).thenReturn(false);
        when(userRepository.existsByPhoneNumberAndHomeCountry("+263771234567", "ZW")).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("hashed");
    }

    @Test
    void createShopAdmin_publishesOnboardingEvent_offTheRequestThread() {
        UUID shopId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        asOrgMerchantAdmin(merchantId);
        stubShopAdminHappyPath(shopId, merchantId);

        UserResponseDTO result = service.createShopAdmin(shopAdminDto(shopId));
        assertThat(result).isNotNull();

        // The temp password is a per-user random value (not the old shared
        // #Pass123). Capture what was hashed and assert the SAME value is carried
        // on the delivery event.
        ArgumentCaptor<String> pw = ArgumentCaptor.forClass(String.class);
        verify(passwordEncoder).encode(pw.capture());
        String generated = pw.getValue();
        assertThat(generated).isNotEqualTo("#Pass123")
                .matches("[A-Za-z0-9]{5}-[A-Za-z0-9]{5}");

        // Credentials are delivered off-thread by the async CredentialDeliveryListener
        // (email -> SMS -> WhatsApp), so the create response never blocks on the
        // notification gateway — the service only publishes the event.
        ArgumentCaptor<CredentialDeliveryRequested> ev =
                ArgumentCaptor.forClass(CredentialDeliveryRequested.class);
        verify(eventPublisher).publishEvent(ev.capture());
        CredentialDeliveryRequested e = ev.getValue();
        assertThat(e.reason()).isEqualTo(CredentialDeliveryRequested.Reason.ONBOARDING);
        assertThat(e.email()).isEqualTo("tendai@shop.co.zw");
        assertThat(e.firstName()).isEqualTo("Tendai");
        assertThat(e.phoneNumber()).isEqualTo("+263771234567");
        assertThat(e.tempPassword()).isEqualTo(generated);
    }

    @Test
    void createShopUser_publishesOnboardingEvent() {
        UUID shopId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        authenticateAs(User.builder().email("shopadmin@x.com")
                .roles(User.roleNames(User.Role.SHOP_ADMIN))
                .loyaltyShopId(shopId).loyaltyMerchantId(merchantId).build());
        when(userRepository.existsByEmailIgnoreCase("rufaro@shop.co.zw")).thenReturn(false);
        when(userRepository.existsByPhoneNumberAndHomeCountry("+263772345678", "ZW")).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("hashed");

        CreateShopUserDTO dto = new CreateShopUserDTO();
        dto.setFirstName("Rufaro");
        dto.setLastName("Ncube");
        dto.setEmail("rufaro@shop.co.zw");
        dto.setPhoneNumber("+263772345678");

        service.createShopUser(dto);

        ArgumentCaptor<CredentialDeliveryRequested> ev =
                ArgumentCaptor.forClass(CredentialDeliveryRequested.class);
        verify(eventPublisher).publishEvent(ev.capture());
        assertThat(ev.getValue().reason()).isEqualTo(CredentialDeliveryRequested.Reason.ONBOARDING);
        assertThat(ev.getValue().email()).isEqualTo("rufaro@shop.co.zw");
    }

    private User shopUser(UUID userUuid, UUID merchantId, UUID shopId) {
        return User.builder()
                .id(74L).userUuid(userUuid)
                .email("rufaro@pizza.co.zw").firstName("Rufaro").lastName("Ncube")
                .phoneNumber("+263772345678")
                .roles(User.roleNames(User.Role.SHOP_USER))
                .loyaltyMerchantId(merchantId).loyaltyShopId(shopId)
                .active(true).build();
    }

    private User shopAdminTarget(UUID userUuid, UUID merchantId, UUID shopId) {
        return User.builder()
                .id(73L).userUuid(userUuid)
                .email("tendai@pizza.co.zw").firstName("Tendai").lastName("Moyo")
                .phoneNumber("+263771234567")
                .roles(User.roleNames(User.Role.SHOP_ADMIN))
                .loyaltyMerchantId(merchantId).loyaltyShopId(shopId)
                .active(true).build();
    }

    @Test
    void resetTemporaryPassword_shopAdminMayResetShopUserAtSameShop() {
        UUID shopId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        UUID targetUuid = UUID.randomUUID();
        User caller = User.builder().id(1L).email("shopadmin@x.com")
                .roles(User.roleNames(User.Role.SHOP_ADMIN))
                .loyaltyMerchantId(merchantId).loyaltyShopId(shopId).build();
        authenticateAs(caller);
        User target = shopUser(targetUuid, merchantId, shopId);
        when(userRepository.findByUserUuid(targetUuid)).thenReturn(Optional.of(target));
        when(passwordEncoder.encode(any())).thenReturn("NEW_HASH");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        UserResponseDTO result = service.resetTemporaryPassword(targetUuid);

        assertThat(result.getUserUuid()).isEqualTo(targetUuid);
        assertThat(target.getPassword()).isEqualTo("NEW_HASH");
        assertThat(target.isMustChangePassword()).isTrue();
        ArgumentCaptor<CredentialDeliveryRequested> ev =
                ArgumentCaptor.forClass(CredentialDeliveryRequested.class);
        verify(eventPublisher).publishEvent(ev.capture());
        assertThat(ev.getValue().reason()).isEqualTo(CredentialDeliveryRequested.Reason.RESET);
        assertThat(ev.getValue().email()).isEqualTo("rufaro@pizza.co.zw");
    }

    @Test
    void resetTemporaryPassword_merchantAdminMayResetShopAdminAtSameMerchant() {
        UUID merchantId = UUID.randomUUID();
        UUID targetShopId = UUID.randomUUID();
        UUID targetUuid = UUID.randomUUID();
        // Scope from the ORGANIZATION. This used to compare a stamped
        // loyaltyMerchantId on the caller, which a real merchant admin never
        // has — so in production every merchant-admin reset was a 404.
        asOrgMerchantAdmin(merchantId);
        User target = shopAdminTarget(targetUuid, merchantId, targetShopId);
        when(userRepository.findByUserUuid(targetUuid)).thenReturn(Optional.of(target));
        when(passwordEncoder.encode(any())).thenReturn("NEW_HASH");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        UserResponseDTO result = service.resetTemporaryPassword(targetUuid);

        assertThat(result.getUserUuid()).isEqualTo(targetUuid);
        ArgumentCaptor<CredentialDeliveryRequested> ev =
                ArgumentCaptor.forClass(CredentialDeliveryRequested.class);
        verify(eventPublisher).publishEvent(ev.capture());
        assertThat(ev.getValue().reason()).isEqualTo(CredentialDeliveryRequested.Reason.RESET);
        assertThat(ev.getValue().email()).isEqualTo("tendai@pizza.co.zw");
    }

    @Test
    void resetTemporaryPassword_shopAdminCannotResetShopUserAtDifferentShop() {
        UUID merchantId = UUID.randomUUID();
        UUID callerShopId = UUID.randomUUID();
        UUID otherShopId = UUID.randomUUID();
        UUID targetUuid = UUID.randomUUID();
        User caller = User.builder().id(1L).email("shopadmin@x.com")
                .roles(User.roleNames(User.Role.SHOP_ADMIN))
                .loyaltyMerchantId(merchantId).loyaltyShopId(callerShopId).build();
        authenticateAs(caller);
        // Target is a SHOP_USER at a DIFFERENT shop within the same merchant.
        when(userRepository.findByUserUuid(targetUuid))
                .thenReturn(Optional.of(shopUser(targetUuid, merchantId, otherShopId)));

        assertThatThrownBy(() -> service.resetTemporaryPassword(targetUuid))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
        verify(passwordEncoder, never()).encode(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void resetTemporaryPassword_shopAdminCannotResetPeerShopAdmin() {
        // SHOP_ADMINs are peers; only the MERCHANT_ADMIN above them can reset.
        UUID merchantId = UUID.randomUUID();
        UUID shopId = UUID.randomUUID();
        UUID peerUuid = UUID.randomUUID();
        User caller = User.builder().id(1L).email("shopadmin@x.com")
                .roles(User.roleNames(User.Role.SHOP_ADMIN))
                .loyaltyMerchantId(merchantId).loyaltyShopId(shopId).build();
        authenticateAs(caller);
        when(userRepository.findByUserUuid(peerUuid))
                .thenReturn(Optional.of(shopAdminTarget(peerUuid, merchantId, shopId)));

        assertThatThrownBy(() -> service.resetTemporaryPassword(peerUuid))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void resetTemporaryPassword_merchantAdminCannotResetStaffAtDifferentMerchant() {
        UUID callerMerchant = UUID.randomUUID();
        UUID otherMerchant = UUID.randomUUID();
        UUID targetUuid = UUID.randomUUID();
        asOrgMerchantAdmin(callerMerchant);
        when(userRepository.findByUserUuid(targetUuid))
                .thenReturn(Optional.of(shopUser(targetUuid, otherMerchant, UUID.randomUUID())));

        assertThatThrownBy(() -> service.resetTemporaryPassword(targetUuid))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    // --- H2: cross-merchant scoping on the admin create / list endpoints -----

    @Test
    void createShopAdmin_rejectsShopBelongingToAnotherMerchant() {
        UUID shopId = UUID.randomUUID();
        UUID callerMerchant = UUID.randomUUID();
        UUID foreignMerchant = UUID.randomUUID();
        asOrgMerchantAdmin(callerMerchant);
        // The shop resolves to a DIFFERENT merchant than the caller's.
        when(loyaltyServiceClient.findShop(shopId)).thenReturn(Optional.of(
                new LoyaltyServiceClient.ShopLookupResponse(
                        shopId.toString(), foreignMerchant.toString(), "tenant-2", "ACTIVE")));

        assertThatThrownBy(() -> service.createShopAdmin(shopAdminDto(shopId)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
        // No staff account is provisioned into a cross-merchant shop.
        verify(userRepository, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void listForMerchant_rejectsForeignMerchant_andReadsNoStaff() {
        UUID callerMerchant = UUID.randomUUID();
        UUID foreignMerchant = UUID.randomUUID();
        asOrgMerchantAdmin(callerMerchant);

        assertThatThrownBy(() -> service.listForMerchant(foreignMerchant))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
        verify(userRepository, never()).findByLoyaltyMerchantId(any());
    }

    @Test
    void listForMerchant_returnsStaff_forOwnMerchant() {
        UUID merchantId = UUID.randomUUID();
        asOrgMerchantAdmin(merchantId);
        when(userRepository.findByLoyaltyMerchantId(merchantId)).thenReturn(List.of());

        assertThatCode(() -> service.listForMerchant(merchantId)).doesNotThrowAnyException();
        verify(userRepository).findByLoyaltyMerchantId(merchantId);
    }

    @Test
    void listForShop_rejectsShopBelongingToAnotherMerchant_andReadsNoStaff() {
        UUID shopId = UUID.randomUUID();
        UUID callerMerchant = UUID.randomUUID();
        UUID foreignMerchant = UUID.randomUUID();
        asOrgMerchantAdmin(callerMerchant);
        when(loyaltyServiceClient.findShop(shopId)).thenReturn(Optional.of(
                new LoyaltyServiceClient.ShopLookupResponse(
                        shopId.toString(), foreignMerchant.toString(), "tenant-2", "ACTIVE")));

        assertThatThrownBy(() -> service.listForShop(shopId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
        verify(userRepository, never()).findByLoyaltyShopId(any());
    }

    // --- Merchant-admin authority resolved by ORGANIZATION ---------------------
    // Production shape: a MERCHANT_ADMIN carries NO loyaltyMerchantId on their
    // JWT or User row — their merchant(s) are the ones loyalty-service lists for
    // the organization their session acts for, when they are its OWNER/ADMIN.
    // Before that, every one of these returned 403 because the code read the
    // (always-null) caller.getLoyaltyMerchantId().

    private static final UUID ORG = UUID.fromString("7b1e2c4d-9f3a-4e5b-8c6d-0a1b2c3d4e5f");

    private User merchantAdminNoBinding(String email) {
        return User.builder().id(501L).email(email)
                .roles(User.roleNames(User.Role.MERCHANT_ADMIN))
                .build();
    }

    /**
     * A merchant admin whose session acts for {@link #ORG}, holding {@code role}
     * there. Ownership of loyalty merchants is the ORGANIZATION's, so this is the
     * shape every merchant-scoped shop-staff call now resolves against.
     */
    private void authenticateInOrganization(User caller, OrganizationMember.Role role) {
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(caller.getEmail(), null, authoritiesOf(caller));
        auth.setDetails(new java.util.HashMap<>(java.util.Map.of(AuthDetailsKeys.ORGANIZATION_ID, ORG)));
        SecurityContextHolder.getContext().setAuthentication(auth);
        when(userRepository.findByEmail(caller.getEmail())).thenReturn(Optional.of(caller));
        when(organizationMembers.findByOrganizationIdAndUserId(ORG, caller.getId()))
                .thenReturn(Optional.of(OrganizationMember.builder()
                        .organizationId(ORG).userId(caller.getId()).role(role).build()));
    }

    @Test
    void listForShop_merchantAdminWithoutLocalBinding_resolvesMerchantByOrganization_allowsOwnShop() {
        UUID shopId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        authenticateInOrganization(merchantAdminNoBinding("merchant@x.com"), OrganizationMember.Role.OWNER);
        when(loyaltyServiceClient.merchantIdsForOrganization(ORG))
                .thenReturn(List.of(merchantId));
        when(loyaltyServiceClient.findShop(shopId)).thenReturn(Optional.of(
                new LoyaltyServiceClient.ShopLookupResponse(
                        shopId.toString(), merchantId.toString(), "tenant-1", "ACTIVE")));
        when(userRepository.findByLoyaltyShopId(shopId)).thenReturn(List.of());

        assertThatCode(() -> service.listForShop(shopId)).doesNotThrowAnyException();
        verify(userRepository).findByLoyaltyShopId(shopId);
    }

    @Test
    void listForShop_merchantAdminWithoutLocalBinding_foreignShop_forbidden() {
        UUID shopId = UUID.randomUUID();
        UUID ownedMerchant = UUID.randomUUID();
        UUID foreignMerchant = UUID.randomUUID();
        authenticateInOrganization(merchantAdminNoBinding("merchant@x.com"), OrganizationMember.Role.OWNER);
        when(loyaltyServiceClient.merchantIdsForOrganization(ORG))
                .thenReturn(List.of(ownedMerchant));
        when(loyaltyServiceClient.findShop(shopId)).thenReturn(Optional.of(
                new LoyaltyServiceClient.ShopLookupResponse(
                        shopId.toString(), foreignMerchant.toString(), "tenant-2", "ACTIVE")));

        assertThatThrownBy(() -> service.listForShop(shopId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
        verify(userRepository, never()).findByLoyaltyShopId(any());
    }

    @Test
    void listForMerchant_merchantAdminWithoutLocalBinding_resolvesOwnershipByOrganization() {
        UUID merchantId = UUID.randomUUID();
        authenticateInOrganization(merchantAdminNoBinding("merchant@x.com"), OrganizationMember.Role.OWNER);
        when(loyaltyServiceClient.merchantIdsForOrganization(ORG))
                .thenReturn(List.of(merchantId));
        when(userRepository.findByLoyaltyMerchantId(merchantId)).thenReturn(List.of());

        assertThatCode(() -> service.listForMerchant(merchantId)).doesNotThrowAnyException();
        verify(userRepository).findByLoyaltyMerchantId(merchantId);
    }

    @Test
    void listForCallerShop_merchantAdmin_returnsStaffAcrossAllTheirMerchants() {
        UUID m1 = UUID.randomUUID();
        UUID m2 = UUID.randomUUID();
        authenticateInOrganization(merchantAdminNoBinding("merchant@x.com"), OrganizationMember.Role.OWNER);
        when(loyaltyServiceClient.merchantIdsForOrganization(ORG))
                .thenReturn(List.of(m1, m2));
        when(userRepository.findByLoyaltyMerchantId(m1))
                .thenReturn(List.of(shopAdminTarget(UUID.randomUUID(), m1, UUID.randomUUID())));
        when(userRepository.findByLoyaltyMerchantId(m2))
                .thenReturn(List.of(shopUser(UUID.randomUUID(), m2, UUID.randomUUID())));

        List<UserResponseDTO> staff = service.listForCallerShop();

        assertThat(staff).hasSize(2);
        verify(userRepository, never()).findByLoyaltyShopId(any());
    }

    @Test
    void listForCallerShop_merchantAdminOwningNothing_returnsEmpty() {
        authenticateInOrganization(merchantAdminNoBinding("merchant@x.com"), OrganizationMember.Role.OWNER);
        when(loyaltyServiceClient.merchantIdsForOrganization(ORG)).thenReturn(List.of());

        assertThat(service.listForCallerShop()).isEmpty();
        verify(userRepository, never()).findByLoyaltyMerchantId(any());
        verify(userRepository, never()).findByLoyaltyShopId(any());
    }

    @Test
    void listForCallerShop_shopAdmin_stillReturnsOwnShopStaff() {
        UUID shopId = UUID.randomUUID();
        authenticateAs(User.builder().email("shopadmin@x.com")
                .roles(User.roleNames(User.Role.SHOP_ADMIN))
                .loyaltyShopId(shopId).build());
        when(userRepository.findByLoyaltyShopId(shopId))
                .thenReturn(List.of(shopUser(UUID.randomUUID(), UUID.randomUUID(), shopId)));

        List<UserResponseDTO> staff = service.listForCallerShop();

        assertThat(staff).hasSize(1);
        verify(loyaltyServiceClient, never()).merchantIdsForOrganization(any());
    }

    @Test
    void listForMerchant_staffOfTheOrganization_isForbidden_andLoyaltyIsNeverAsked() {
        UUID merchantId = UUID.randomUUID();
        authenticateInOrganization(merchantAdminNoBinding("merchant@x.com"), OrganizationMember.Role.STAFF);

        assertThatThrownBy(() -> service.listForMerchant(merchantId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
        verify(loyaltyServiceClient, never()).merchantIdsForOrganization(any());
    }

    @Test
    void listForMerchant_sessionWithNoOrganization_isForbidden_andNoEmailLookupHappens() {
        UUID merchantId = UUID.randomUUID();
        authenticateAs(merchantAdminNoBinding("merchant@x.com"));

        assertThatThrownBy(() -> service.listForMerchant(merchantId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
        verify(loyaltyServiceClient, never()).merchantIdsForOrganization(any());
        verify(organizationMembers, never()).findByOrganizationIdAndUserId(any(), any());
    }

    @Test
    void listForMerchant_adminOfTheOrganization_isAllowed() {
        UUID merchantId = UUID.randomUUID();
        authenticateInOrganization(merchantAdminNoBinding("merchant@x.com"), OrganizationMember.Role.ADMIN);
        when(loyaltyServiceClient.merchantIdsForOrganization(ORG)).thenReturn(List.of(merchantId));
        when(userRepository.findByLoyaltyMerchantId(merchantId)).thenReturn(List.of());

        assertThatCode(() -> service.listForMerchant(merchantId)).doesNotThrowAnyException();
    }

    // --- SUPER_ADMIN acts for every merchant -----------------------------------
    // The ownership checks resolve the merchants the caller's ORGANIZATION owns;
    // the platform owner belongs to none, so every shop used to answer 403
    // "Shop does not belong to your merchant" on the console's Shop Users screen.

    private User superAdmin() {
        return User.builder().email("admin@innbucks.co.zw")
                .roles(User.roleNames(User.Role.SUPER_ADMIN)).build();
    }

    @Test
    void listForShop_superAdmin_readsAnyMerchantsShop_withoutAnOwnershipLookup() {
        UUID shopId = UUID.randomUUID();
        authenticateAs(superAdmin());
        when(loyaltyServiceClient.findShop(shopId)).thenReturn(Optional.of(
                new LoyaltyServiceClient.ShopLookupResponse(
                        shopId.toString(), UUID.randomUUID().toString(), "tenant-2", "ACTIVE")));
        User cashier = User.builder().email("cashier@shop.co.zw")
                .roles(User.roleNames(User.Role.SHOP_USER)).loyaltyShopId(shopId).build();
        when(userRepository.findByLoyaltyShopId(shopId)).thenReturn(List.of(cashier));

        assertThat(service.listForShop(shopId)).hasSize(1);
        verify(loyaltyServiceClient, never()).merchantIdsForOrganization(any());
    }

    @Test
    void listForShop_superAdmin_unknownShop_isStillABadRequest() {
        UUID shopId = UUID.randomUUID();
        authenticateAs(superAdmin());
        when(loyaltyServiceClient.findShop(shopId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.listForShop(shopId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
        verify(userRepository, never()).findByLoyaltyShopId(any());
    }

    @Test
    void listForMerchant_superAdmin_readsAnyMerchant() {
        UUID merchantId = UUID.randomUUID();
        authenticateAs(superAdmin());
        when(userRepository.findByLoyaltyMerchantId(merchantId)).thenReturn(List.of());

        assertThatCode(() -> service.listForMerchant(merchantId)).doesNotThrowAnyException();
        verify(userRepository).findByLoyaltyMerchantId(merchantId);
        verify(loyaltyServiceClient, never()).merchantIdsForOrganization(any());
    }

    @Test
    void listForCallerShop_superAdmin_returnsEveryShopsStaff_notA400() {
        authenticateAs(superAdmin());
        User cashier = User.builder().email("cashier@shop.co.zw")
                .roles(User.roleNames(User.Role.SHOP_USER)).build();
        when(userRepository.findByAnyRole(User.roleNames(User.Role.SHOP_ADMIN, User.Role.SHOP_USER)))
                .thenReturn(List.of(cashier));

        assertThat(service.listForCallerShop()).hasSize(1);
        verify(userRepository, never()).findByLoyaltyShopId(any());
    }

    @Test
    void createShopAdmin_superAdmin_provisionsIntoAnyMerchantsShop_boundToTheShopsMerchant() {
        UUID shopId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        authenticateAs(superAdmin());
        stubShopAdminHappyPath(shopId, merchantId);

        service.createShopAdmin(shopAdminDto(shopId));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().hasRole(User.Role.SHOP_ADMIN)).isTrue();
        assertThat(saved.getValue().getLoyaltyShopId()).isEqualTo(shopId);
        // The merchant comes from the SHOP, never from the caller.
        assertThat(saved.getValue().getLoyaltyMerchantId()).isEqualTo(merchantId);
    }

    @Test
    void createShopAdmin_aShopAdminCaller_isStillRefused() {
        authenticateAs(User.builder().email("shopadmin@x.com")
                .roles(User.roleNames(User.Role.SHOP_ADMIN)).build());

        assertThatThrownBy(() -> service.createShopAdmin(shopAdminDto(UUID.randomUUID())))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
        verifyNoInteractions(loyaltyServiceClient);
    }

    // --- GET /by-shop/{shopId} for a SHOP_ADMIN: its own shop, and only that --
    // The console's Shop Users screen lists staff through by-shop for every
    // role. A shop admin used to get "Forbidden - insufficient role" there after
    // every till user it created (the create itself succeeded). It may now read
    // its own shop and only that: a shop admin is not merchant-wide, so it is
    // pinned to its own shop and never reaches the merchant check.

    private User shopAdmin(UUID merchantId, UUID shopId) {
        return User.builder().email("shopadmin@x.com")
                .roles(User.roleNames(User.Role.SHOP_ADMIN))
                .loyaltyMerchantId(merchantId).loyaltyShopId(shopId).build();
    }

    @Test
    void listForShop_shopAdmin_ownShop_returnsItsStaff_withoutALoyaltyLookup() {
        UUID merchantId = UUID.randomUUID();
        UUID shopId = UUID.randomUUID();
        authenticateAs(shopAdmin(merchantId, shopId));
        when(userRepository.findByLoyaltyShopId(shopId))
                .thenReturn(List.of(shopUser(UUID.randomUUID(), merchantId, shopId)));

        List<UserResponseDTO> staff = service.listForShop(shopId);

        assertThat(staff).hasSize(1);
        verifyNoInteractions(loyaltyServiceClient);
    }

    @Test
    void listForShop_shopAdmin_siblingShopOfTheSameMerchant_isForbidden_andReadsNoStaff() {
        UUID merchantId = UUID.randomUUID();
        UUID ownShop = UUID.randomUUID();
        UUID siblingShop = UUID.randomUUID();
        authenticateAs(shopAdmin(merchantId, ownShop));

        assertThatThrownBy(() -> service.listForShop(siblingShop))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(rse.getReason()).isEqualTo("You can only view the staff of your own shop");
                });
        verify(userRepository, never()).findByLoyaltyShopId(any());
        verifyNoInteractions(loyaltyServiceClient);
    }

    @Test
    void listForShop_shopAdminWithNoShopBinding_isForbidden() {
        authenticateAs(shopAdmin(UUID.randomUUID(), null));

        assertThatThrownBy(() -> service.listForShop(UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
        verify(userRepository, never()).findByLoyaltyShopId(any());
    }

    @Test
    void listForShop_customRoleHoldingMerchantRead_keepsTheMerchantCheck() {
        UUID merchantId = UUID.randomUUID();
        UUID shopId = UUID.randomUUID();
        // A runtime role holding the merchant-wide read, held by someone who runs
        // ORG. Its merchant scope is the organization's, like a merchant admin's.
        User auditor = User.builder().id(502L).email("auditor@x.com")
                .roles(java.util.Set.of("SHOP_AUDITOR")).build();
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                auditor.getEmail(), null,
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority(
                        "shop-staff:merchant:read")));
        auth.setDetails(new java.util.HashMap<>(java.util.Map.of(AuthDetailsKeys.ORGANIZATION_ID, ORG)));
        SecurityContextHolder.getContext().setAuthentication(auth);
        when(userRepository.findByEmail(auditor.getEmail())).thenReturn(Optional.of(auditor));
        when(organizationMembers.findByOrganizationIdAndUserId(ORG, 502L))
                .thenReturn(Optional.of(OrganizationMember.builder()
                        .organizationId(ORG).userId(502L).role(OrganizationMember.Role.ADMIN).build()));
        when(loyaltyServiceClient.merchantIdsForOrganization(ORG)).thenReturn(List.of(merchantId));
        when(loyaltyServiceClient.findShop(shopId)).thenReturn(Optional.of(
                new LoyaltyServiceClient.ShopLookupResponse(
                        shopId.toString(), merchantId.toString(), "tenant-1", "ACTIVE")));
        when(userRepository.findByLoyaltyShopId(shopId)).thenReturn(List.of());

        assertThatCode(() -> service.listForShop(shopId)).doesNotThrowAnyException();
        // It went through the merchant path (shop resolved in loyalty), not the
        // own-shop path, which it would have failed: it has no loyaltyShopId.
        verify(loyaltyServiceClient).findShop(shopId);
    }

    @Test
    void listForShop_merchantAdminWhoseMerchantReadWasRemoved_isNoLongerMerchantWide() {
        // An operator removed shop-staff:merchant:read from MERCHANT_ADMIN but kept
        // shop-staff:read. The role NAME must not restore merchant-wide by-shop
        // reads: built-in permissions are editable, and editing them is how an
        // operator changes what the role can do.
        UUID shopId = UUID.randomUUID();
        User admin = merchantAdminNoBinding("merchant@x.com");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                admin.getEmail(), null,
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("shop-staff:read"))));
        when(userRepository.findByEmail(admin.getEmail())).thenReturn(Optional.of(admin));

        assertThatThrownBy(() -> service.listForShop(shopId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
        verifyNoInteractions(loyaltyServiceClient);
        verify(userRepository, never()).findByLoyaltyShopId(any());
    }

    // --- An account that is shop staff AND a merchant admin (two hats) ---------
    // A cashier at the EMPLOYER's shop who got MERCHANT_ADMIN through an approved
    // service request to run a business of their own (ORG). Its merchant-admin
    // hat reaches ORG's merchants only; its row-stamped loyaltyMerchantId is the
    // employer's and grants nothing as a merchant admin. It used to be folded
    // into the merchant set, which let the cashier administer every shop of the
    // employer: list its staff, create shop admins there, reset passwords.

    private static final UUID EMPLOYER = UUID.fromString("e0000000-0000-4000-8000-000000000001");
    private static final UUID OWN_BUSINESS = UUID.fromString("b0000000-0000-4000-8000-000000000002");
    private static final UUID HOME_SHOP = UUID.fromString("5a000000-0000-4000-8000-000000000003");

    /** Shop staff at {@link #HOME_SHOP} of {@link #EMPLOYER}, who also runs ORG as OWNER. */
    private User twoHats(User.Role shopRole, UUID... orgMerchants) {
        User caller = User.builder().id(601L).email("cashier@x.com")
                .roles(User.roleNames(shopRole, User.Role.MERCHANT_ADMIN))
                .loyaltyMerchantId(EMPLOYER).loyaltyShopId(HOME_SHOP).build();
        authenticateInOrganization(caller, OrganizationMember.Role.OWNER);
        when(loyaltyServiceClient.merchantIdsForOrganization(ORG)).thenReturn(List.of(orgMerchants));
        return caller;
    }

    @Test
    void twoHats_cashierWhoBecameMerchantAdmin_mine_neverListsTheEmployersStaff() {
        twoHats(User.Role.SHOP_USER, OWN_BUSINESS);
        when(userRepository.findByLoyaltyMerchantId(OWN_BUSINESS)).thenReturn(List.of());

        assertThat(service.listForCallerShop()).isEmpty();

        verify(userRepository, never()).findByLoyaltyMerchantId(EMPLOYER);
        // A cashier is not a shop admin: no shop view comes with the till hat.
        verify(userRepository, never()).findByLoyaltyShopId(any());
    }

    @Test
    void twoHats_cannotListTheEmployersMerchant() {
        twoHats(User.Role.SHOP_USER);

        assertThatThrownBy(() -> service.listForMerchant(EMPLOYER))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
        verify(userRepository, never()).findByLoyaltyMerchantId(any());
    }

    @Test
    void twoHats_cannotCreateAShopAdminAtTheEmployersShop() {
        twoHats(User.Role.SHOP_USER, OWN_BUSINESS);
        UUID employersShop = UUID.randomUUID();
        when(loyaltyServiceClient.findShop(employersShop)).thenReturn(Optional.of(
                new LoyaltyServiceClient.ShopLookupResponse(
                        employersShop.toString(), EMPLOYER.toString(), "tenant-1", "ACTIVE")));

        assertThatThrownBy(() -> service.createShopAdmin(shopAdminDto(employersShop)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
        verify(userRepository, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void twoHats_cannotReadASiblingShopOfTheEmployer() {
        twoHats(User.Role.SHOP_ADMIN, OWN_BUSINESS);
        UUID siblingShop = UUID.randomUUID();
        when(loyaltyServiceClient.findShop(siblingShop)).thenReturn(Optional.of(
                new LoyaltyServiceClient.ShopLookupResponse(
                        siblingShop.toString(), EMPLOYER.toString(), "tenant-1", "ACTIVE")));

        assertThatThrownBy(() -> service.listForShop(siblingShop))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
        verify(userRepository, never()).findByLoyaltyShopId(any());
    }

    @Test
    void twoHats_cannotResetTheEmployersStaff() {
        twoHats(User.Role.SHOP_USER, OWN_BUSINESS);
        UUID targetUuid = UUID.randomUUID();
        when(userRepository.findByUserUuid(targetUuid))
                .thenReturn(Optional.of(shopAdminTarget(targetUuid, EMPLOYER, UUID.randomUUID())));

        assertThatThrownBy(() -> service.resetTemporaryPassword(targetUuid))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
        verify(passwordEncoder, never()).encode(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void twoHats_aShopAdmin_keepsItsOwnShop_onEveryShopPath() {
        // by-shop: its own shop, with no ownership lookup.
        twoHats(User.Role.SHOP_ADMIN, OWN_BUSINESS);
        User colleague = shopUser(UUID.randomUUID(), EMPLOYER, HOME_SHOP);
        when(userRepository.findByLoyaltyShopId(HOME_SHOP)).thenReturn(List.of(colleague));

        assertThat(service.listForShop(HOME_SHOP)).hasSize(1);
        verify(loyaltyServiceClient, never()).findShop(any());

        // /mine: its own business's staff plus its own shop — not the employer's.
        when(userRepository.findByLoyaltyMerchantId(OWN_BUSINESS)).thenReturn(List.of());
        assertThat(service.listForCallerShop()).extracting(UserResponseDTO::getUserUuid)
                .containsExactly(colleague.getUserUuid());
        verify(userRepository, never()).findByLoyaltyMerchantId(EMPLOYER);
    }

    @Test
    void twoHats_aShopAdmin_stillResetsATillUserAtItsOwnShop() {
        authenticateAs(User.builder().id(601L).email("cashier@x.com")
                .roles(User.roleNames(User.Role.SHOP_ADMIN, User.Role.MERCHANT_ADMIN))
                .loyaltyMerchantId(EMPLOYER).loyaltyShopId(HOME_SHOP).build());
        UUID targetUuid = UUID.randomUUID();
        User target = shopUser(targetUuid, EMPLOYER, HOME_SHOP);
        when(userRepository.findByUserUuid(targetUuid)).thenReturn(Optional.of(target));
        when(passwordEncoder.encode(any())).thenReturn("NEW_HASH");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.resetTemporaryPassword(targetUuid).getUserUuid()).isEqualTo(targetUuid);
        // The shop hat decides it; no merchant scope is resolved.
        verifyNoInteractions(loyaltyServiceClient);
    }

    // --- Only a PLAIN shop-staff account is reset here ------------------------
    // One that also runs a business (MERCHANT_ADMIN) or belongs to an
    // organization holds authority its employer never granted; replacing its
    // password would lock that business out. It resets its own. Each case resets
    // a plain colleague first, so the refusal is the target rule, not a caller
    // without scope.

    private User shopUserWith(long id, UUID userUuid, User.Role... roles) {
        return User.builder().id(id).userUuid(userUuid).email("staff" + id + "@pizza.co.zw")
                .roles(User.roleNames(roles))
                .loyaltyMerchantId(EMPLOYER).loyaltyShopId(HOME_SHOP)
                .active(true).build();
    }

    private void assertResetRefused(UUID targetUuid) {
        assertThatThrownBy(() -> service.resetTemporaryPassword(targetUuid))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    private void stubResetWrites() {
        when(passwordEncoder.encode(any())).thenReturn("NEW_HASH");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void reset_merchantHat_refusesShopStaffWhoAlsoRunABusiness() {
        asOrgMerchantAdmin(EMPLOYER);
        stubResetWrites();
        UUID plainUuid = UUID.randomUUID();
        when(userRepository.findByUserUuid(plainUuid))
                .thenReturn(Optional.of(shopUserWith(80L, plainUuid, User.Role.SHOP_USER)));
        assertThat(service.resetTemporaryPassword(plainUuid).getUserUuid()).isEqualTo(plainUuid);

        UUID ownerUuid = UUID.randomUUID();
        User owner = shopUserWith(81L, ownerUuid, User.Role.SHOP_USER, User.Role.MERCHANT_ADMIN);
        when(userRepository.findByUserUuid(ownerUuid)).thenReturn(Optional.of(owner));

        assertResetRefused(ownerUuid);
        assertThat(owner.getPassword()).isNull();
        verify(passwordEncoder, times(1)).encode(any());
        verify(eventPublisher, times(1)).publishEvent(any(CredentialDeliveryRequested.class));
    }

    @Test
    void reset_merchantHat_refusesShopStaffWhoBelongToAnOrganization() {
        // An ADMIN colleague of some business has no extra role at all, yet is a
        // merchant admin in loyalty — the membership is what makes it theirs.
        asOrgMerchantAdmin(EMPLOYER);
        stubResetWrites();
        UUID plainUuid = UUID.randomUUID();
        when(userRepository.findByUserUuid(plainUuid))
                .thenReturn(Optional.of(shopUserWith(82L, plainUuid, User.Role.SHOP_ADMIN)));
        assertThat(service.resetTemporaryPassword(plainUuid).getUserUuid()).isEqualTo(plainUuid);

        UUID memberUuid = UUID.randomUUID();
        User member = shopUserWith(83L, memberUuid, User.Role.SHOP_ADMIN);
        when(userRepository.findByUserUuid(memberUuid)).thenReturn(Optional.of(member));
        when(organizationMembers.existsByUserId(83L)).thenReturn(true);

        assertResetRefused(memberUuid);
        assertThat(member.getPassword()).isNull();
        verify(passwordEncoder, times(1)).encode(any());
    }

    @Test
    void reset_shopHat_refusesATillUserWhoAlsoRunsABusiness_butACustomerRoleIsFine() {
        authenticateAs(User.builder().id(1L).email("shopadmin@x.com")
                .roles(User.roleNames(User.Role.SHOP_ADMIN))
                .loyaltyMerchantId(EMPLOYER).loyaltyShopId(HOME_SHOP).build());
        stubResetWrites();
        // A cashier who also shops is still plain shop staff.
        UUID shopperUuid = UUID.randomUUID();
        when(userRepository.findByUserUuid(shopperUuid)).thenReturn(Optional.of(
                shopUserWith(84L, shopperUuid, User.Role.SHOP_USER, User.Role.CUSTOMER)));
        assertThat(service.resetTemporaryPassword(shopperUuid).getUserUuid()).isEqualTo(shopperUuid);

        UUID ownerUuid = UUID.randomUUID();
        User owner = shopUserWith(85L, ownerUuid, User.Role.SHOP_USER, User.Role.MERCHANT_ADMIN);
        when(userRepository.findByUserUuid(ownerUuid)).thenReturn(Optional.of(owner));

        assertResetRefused(ownerUuid);
        assertThat(owner.getPassword()).isNull();
        verify(passwordEncoder, times(1)).encode(any());
        verifyNoInteractions(loyaltyServiceClient);
    }

    @Test
    void merchantAdminActingForNoOrganization_resetsNobody() {
        User admin = merchantAdminNoBinding("merchant@x.com");
        authenticateAs(admin);
        UUID targetUuid = UUID.randomUUID();
        when(userRepository.findByUserUuid(targetUuid))
                .thenReturn(Optional.of(shopUser(targetUuid, UUID.randomUUID(), UUID.randomUUID())));

        assertThatThrownBy(() -> service.resetTemporaryPassword(targetUuid))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
        verify(loyaltyServiceClient, never()).merchantIdsForOrganization(any());
    }
}
