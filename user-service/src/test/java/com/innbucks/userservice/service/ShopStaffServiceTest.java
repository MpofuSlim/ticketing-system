package com.innbucks.userservice.service;

import com.innbucks.userservice.client.EmailNotificationClient;
import com.innbucks.userservice.client.NotificationDeliveryException;
import com.innbucks.userservice.client.SmsNotificationClient;
import com.innbucks.userservice.dto.CreateShopAdminDTO;
import com.innbucks.userservice.dto.CreateShopUserDTO;
import com.innbucks.userservice.dto.UserResponseDTO;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.integration.LoyaltyServiceClient;
import com.innbucks.userservice.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.EnumSet;
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
    @Mock private EmailNotificationClient emailNotificationClient;
    @Mock private SmsNotificationClient smsNotificationClient;

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
                new UsernamePasswordAuthenticationToken(caller.getEmail(), null));
        when(userRepository.findByEmail(caller.getEmail())).thenReturn(Optional.of(caller));
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
        when(userRepository.existsByEmail("tendai@shop.co.zw")).thenReturn(false);
        when(userRepository.existsByPhoneNumberAndHomeCountry("+263771234567", "ZW")).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("hashed");
    }

    @Test
    void createShopAdmin_emailsCredentialsToTheNewAdmin() {
        authenticateAs(User.builder().email("merchant@x.com")
                .roles(EnumSet.of(User.Role.MERCHANT_ADMIN)).build());
        UUID shopId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        stubShopAdminHappyPath(shopId, merchantId);

        UserResponseDTO result = service.createShopAdmin(shopAdminDto(shopId));

        assertThat(result).isNotNull();

        ArgumentCaptor<String> to = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> ref = ArgumentCaptor.forClass(String.class);
        verify(emailNotificationClient).sendEmail(to.capture(), subject.capture(), body.capture(), ref.capture());

        // The temp password is now a per-user random value (not the old shared
        // #Pass123). Capture what was hashed and assert the SAME value reaches
        // the email body.
        ArgumentCaptor<String> pw = ArgumentCaptor.forClass(String.class);
        verify(passwordEncoder).encode(pw.capture());
        String generated = pw.getValue();
        assertThat(generated).isNotEqualTo("#Pass123")
                // 2 groups of 5 (10 password chars + 1 hyphen). Exact alphabet
                // pinned in TemporaryPasswordGeneratorTest.
                .matches("[A-Za-z0-9]{5}-[A-Za-z0-9]{5}");

        assertThat(to.getValue()).isEqualTo("tendai@shop.co.zw");
        assertThat(subject.getValue()).contains("Welcome to InnBucks");
        assertThat(body.getValue())
                .contains("Shop Administrator")
                .contains("Tendai")
                .contains(generated);
        assertThat(ref.getValue()).startsWith("STAFF-ONBOARD-");

        // Email succeeded → SMS fallback must not fire.
        verifyNoInteractions(smsNotificationClient);
    }

    @Test
    void createShopUser_emailsCredentialsWithShopUserRoleLabel() {
        UUID shopId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        authenticateAs(User.builder().email("shopadmin@x.com")
                .roles(EnumSet.of(User.Role.SHOP_ADMIN))
                .loyaltyShopId(shopId).loyaltyMerchantId(merchantId).build());
        when(userRepository.existsByEmail("rufaro@shop.co.zw")).thenReturn(false);
        when(userRepository.existsByPhoneNumberAndHomeCountry("+263772345678", "ZW")).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("hashed");

        CreateShopUserDTO dto = new CreateShopUserDTO();
        dto.setFirstName("Rufaro");
        dto.setLastName("Ncube");
        dto.setEmail("rufaro@shop.co.zw");
        dto.setPhoneNumber("+263772345678");

        service.createShopUser(dto);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailNotificationClient).sendEmail(eq("rufaro@shop.co.zw"), anyString(), body.capture(), anyString());
        assertThat(body.getValue()).contains("Shop User");
        verifyNoInteractions(smsNotificationClient);
    }

    @Test
    void createShopAdmin_whenEmailGatewayRejects_fallsBackToSms() {
        authenticateAs(User.builder().email("merchant@x.com")
                .roles(EnumSet.of(User.Role.MERCHANT_ADMIN)).build());
        UUID shopId = UUID.randomUUID();
        stubShopAdminHappyPath(shopId, UUID.randomUUID());
        doThrow(new NotificationDeliveryException("gateway 503"))
                .when(emailNotificationClient).sendEmail(anyString(), anyString(), anyString(), anyString());

        UserResponseDTO result = service.createShopAdmin(shopAdminDto(shopId));

        assertThat(result).isNotNull();
        ArgumentCaptor<String> pw = ArgumentCaptor.forClass(String.class);
        verify(passwordEncoder).encode(pw.capture());
        String generated = pw.getValue();

        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> ref = ArgumentCaptor.forClass(String.class);
        verify(smsNotificationClient).sendSms(eq("+263771234567"), msg.capture(), ref.capture());
        assertThat(msg.getValue()).contains(generated);
        assertThat(ref.getValue()).startsWith("STAFF-ONBOARD-");
    }

    @Test
    void createShopAdmin_whenBothChannelsFail_creationStillSucceeds() {
        authenticateAs(User.builder().email("merchant@x.com")
                .roles(EnumSet.of(User.Role.MERCHANT_ADMIN)).build());
        UUID shopId = UUID.randomUUID();
        stubShopAdminHappyPath(shopId, UUID.randomUUID());
        doThrow(new NotificationDeliveryException("email down"))
                .when(emailNotificationClient).sendEmail(anyString(), anyString(), anyString(), anyString());
        doThrow(new NotificationDeliveryException("sms down"))
                .when(smsNotificationClient).sendSms(anyString(), anyString(), anyString());

        // Best-effort: a total notification outage must NOT fail the creation.
        assertThatCode(() -> service.createShopAdmin(shopAdminDto(shopId))).doesNotThrowAnyException();
        verify(userRepository).save(any(User.class));
    }

    private User shopUser(UUID userUuid, UUID merchantId, UUID shopId) {
        return User.builder()
                .id(74L).userUuid(userUuid)
                .email("rufaro@pizza.co.zw").firstName("Rufaro").lastName("Ncube")
                .phoneNumber("+263772345678")
                .roles(EnumSet.of(User.Role.SHOP_USER))
                .loyaltyMerchantId(merchantId).loyaltyShopId(shopId)
                .active(true).build();
    }

    private User shopAdminTarget(UUID userUuid, UUID merchantId, UUID shopId) {
        return User.builder()
                .id(73L).userUuid(userUuid)
                .email("tendai@pizza.co.zw").firstName("Tendai").lastName("Moyo")
                .phoneNumber("+263771234567")
                .roles(EnumSet.of(User.Role.SHOP_ADMIN))
                .loyaltyMerchantId(merchantId).loyaltyShopId(shopId)
                .active(true).build();
    }

    @Test
    void resetTemporaryPassword_shopAdminMayResetShopUserAtSameShop() {
        UUID shopId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        UUID targetUuid = UUID.randomUUID();
        User caller = User.builder().id(1L).email("shopadmin@x.com")
                .roles(EnumSet.of(User.Role.SHOP_ADMIN))
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
        verify(emailNotificationClient).sendEmail(eq("rufaro@pizza.co.zw"), any(), any(), any());
    }

    @Test
    void resetTemporaryPassword_merchantAdminMayResetShopAdminAtSameMerchant() {
        UUID merchantId = UUID.randomUUID();
        UUID targetShopId = UUID.randomUUID();
        UUID targetUuid = UUID.randomUUID();
        User caller = User.builder().id(1L).email("merchantadmin@x.com")
                .roles(EnumSet.of(User.Role.MERCHANT_ADMIN))
                .loyaltyMerchantId(merchantId).build();
        authenticateAs(caller);
        User target = shopAdminTarget(targetUuid, merchantId, targetShopId);
        when(userRepository.findByUserUuid(targetUuid)).thenReturn(Optional.of(target));
        when(passwordEncoder.encode(any())).thenReturn("NEW_HASH");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        UserResponseDTO result = service.resetTemporaryPassword(targetUuid);

        assertThat(result.getUserUuid()).isEqualTo(targetUuid);
        verify(emailNotificationClient).sendEmail(eq("tendai@pizza.co.zw"), any(), any(), any());
    }

    @Test
    void resetTemporaryPassword_shopAdminCannotResetShopUserAtDifferentShop() {
        UUID merchantId = UUID.randomUUID();
        UUID callerShopId = UUID.randomUUID();
        UUID otherShopId = UUID.randomUUID();
        UUID targetUuid = UUID.randomUUID();
        User caller = User.builder().id(1L).email("shopadmin@x.com")
                .roles(EnumSet.of(User.Role.SHOP_ADMIN))
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
        verify(emailNotificationClient, never()).sendEmail(any(), any(), any(), any());
    }

    @Test
    void resetTemporaryPassword_shopAdminCannotResetPeerShopAdmin() {
        // SHOP_ADMINs are peers; only the MERCHANT_ADMIN above them can reset.
        UUID merchantId = UUID.randomUUID();
        UUID shopId = UUID.randomUUID();
        UUID peerUuid = UUID.randomUUID();
        User caller = User.builder().id(1L).email("shopadmin@x.com")
                .roles(EnumSet.of(User.Role.SHOP_ADMIN))
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
        User caller = User.builder().id(1L).email("merchant@x.com")
                .roles(EnumSet.of(User.Role.MERCHANT_ADMIN))
                .loyaltyMerchantId(callerMerchant).build();
        authenticateAs(caller);
        when(userRepository.findByUserUuid(targetUuid))
                .thenReturn(Optional.of(shopUser(targetUuid, otherMerchant, UUID.randomUUID())));

        assertThatThrownBy(() -> service.resetTemporaryPassword(targetUuid))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }
}
