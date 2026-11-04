package com.innbucks.userservice.service;

import com.innbucks.userservice.dto.AuthResponseDTO;
import com.innbucks.userservice.dto.RegisterRequestDTO;
import com.innbucks.userservice.entity.StaffProfile;
import com.innbucks.userservice.entity.TenantProfile;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.exception.BusinessRegistrationException;
import com.innbucks.userservice.repository.CustomerProfileRepository;
import com.innbucks.userservice.repository.RefreshTokenRepository;
import com.innbucks.userservice.repository.StaffProfileRepository;
import com.innbucks.userservice.repository.TenantProfileRepository;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.security.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An InnBucks app customer registering a business on the Foundry portal with
 * the number their app account already holds. Registration used to refuse it
 * ("Phone number already registered"), so an app customer could never become an
 * organizer or merchant (found on staging, 2026-11-04). The business is now
 * added to that same account once the phone is proven with a code, as pending
 * service requests: no role until a SUPER_ADMIN approves.
 */
class BusinessRegistrationTest {

    private static final String PHONE = "+263779637565";
    private static final String CODE = "482913";

    private UserRepository users;
    private TenantProfileRepository tenants;
    private StaffProfileRepository staffProfiles;
    private ServiceRequestService serviceRequests;
    private OtpService otp;
    private AuditService audit;
    private BusinessRegistrationService businessRegistrations;
    private AuthService auth;
    private User appCustomer;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        tenants = mock(TenantProfileRepository.class);
        staffProfiles = mock(StaffProfileRepository.class);
        serviceRequests = mock(ServiceRequestService.class);
        otp = mock(OtpService.class);
        audit = mock(AuditService.class);
        businessRegistrations = new BusinessRegistrationService(users, staffProfiles, tenants, serviceRequests, otp, audit);
        auth = new AuthService(users, tenants, mock(CustomerProfileRepository.class), mock(PasswordEncoder.class),
                mock(JwtUtil.class), mock(TokenRevocationService.class), mock(RefreshTokenService.class),
                mock(RefreshTokenRepository.class), mock(AuditService.class));
        ReflectionTestUtils.setField(auth, "businessRegistrations", businessRegistrations);

        // The shape an InnBucks app sign-in creates (FederatedLoginService.createCustomer).
        appCustomer = User.builder().id(32L).userUuid(UUID.randomUUID())
                .firstName("Customer").lastName("Pending").phoneNumber(PHONE)
                .roles(User.roleNames(User.Role.CUSTOMER)).active(true).approved(true).password("x").build();
        onPhone(appCustomer);
        when(users.findAllByEmailIgnoreCase(anyString())).thenReturn(List.of());
        when(staffProfiles.findAllByContactPhoneIn(any())).thenReturn(List.of());
        when(serviceRequests.submitBusinessRegistration(any(), anyCollection(), anyString())).thenReturn(List.of(501L));
        when(otp.verifyBusinessRegistrationCode(PHONE, CODE)).thenReturn(true);
        when(tenants.findByUserId(any())).thenReturn(Optional.empty());
    }

    private void onPhone(User account) {
        when(users.findByPhoneNumber(PHONE)).thenReturn(Optional.of(account));
        when(users.existsByPhoneNumberAndHomeCountry(PHONE, "ZW")).thenReturn(true);
        when(users.lockById(account.getId())).thenReturn(Optional.of(account));
    }

    private static RegisterRequestDTO registration(String code) {
        RegisterRequestDTO req = new RegisterRequestDTO();
        req.setFirstName("Tariro");
        req.setLastName("Moyo");
        req.setPhoneNumber("0779637565");
        req.setEmail("tariro@showtime.co.zw");
        req.setCountry("Zimbabwe");
        req.setDefaultServices(List.of("ticketing"));
        req.setBusiness(true);
        req.setBusinessName("Showtime Events");
        req.setBusinessAddress("5 Leopold Takawira St, Bulawayo");
        req.setBpoNumber("200123456");
        req.setPhoneCode(code);
        return req;
    }

    private static String errorCode(Throwable t) {
        return ((BusinessRegistrationException) t).getErrorCode();
    }

    private void nothingChanged() {
        verify(users, never()).save(any());
        verify(tenants, never()).save(any());
        verify(serviceRequests, never()).submitBusinessRegistration(any(), anyCollection(), anyString());
        verify(audit, never()).recordRequired(any(), any(), any(), any(), any(), any(), any());
        assertThat(appCustomer.getEmail()).isNull();
        assertThat(appCustomer.getRoles()).containsExactly("CUSTOMER");
    }

    // --- registration --------------------------------------------------------

    @Test
    void withoutACode_theRegistrantIsAskedForOne_andNothingChanges() {
        assertThatThrownBy(() -> auth.register(registration(null)))
                .isInstanceOf(BusinessRegistrationException.class)
                .satisfies(t -> assertThat(errorCode(t)).isEqualTo(BusinessRegistrationException.PHONE_CODE_REQUIRED))
                .satisfies(t -> assertThat(((BusinessRegistrationException) t).getStatus()).isEqualTo(HttpStatus.CONFLICT));

        verify(otp, never()).verifyBusinessRegistrationCode(any(), any());
        nothingChanged();
    }

    @Test
    void aWrongCode_isRefused_andNothingChanges() {
        assertThatThrownBy(() -> auth.register(registration("111111")))
                .isInstanceOf(BusinessRegistrationException.class)
                .satisfies(t -> assertThat(errorCode(t)).isEqualTo(BusinessRegistrationException.PHONE_CODE_INVALID))
                .satisfies(t -> assertThat(((BusinessRegistrationException) t).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));

        nothingChanged();
    }

    @Test
    void theRightCode_addsTheBusinessToTheSameAccount_asPendingRequests_withNoRole() {
        AuthResponseDTO response = auth.register(registration(CODE));

        assertThat(response.getAddedToExistingAccount()).isTrue();
        assertThat(response.getEmail()).isEqualTo("tariro@showtime.co.zw");
        assertThat(response.getRoles()).containsExactly("CUSTOMER");
        assertThat(response.getDefaultServices()).containsExactly("ticketing");

        // The SAME account: still exactly a customer, now with an email and a name.
        verify(users).save(appCustomer);
        assertThat(appCustomer.getRoles()).containsExactly("CUSTOMER");
        assertThat(appCustomer.getDefaultServices()).isEmpty();
        assertThat(appCustomer.isActive()).isTrue();
        assertThat(appCustomer.getEmail()).isEqualTo("tariro@showtime.co.zw");
        assertThat(appCustomer.getFirstName()).isEqualTo("Tariro");
        assertThat(appCustomer.getLastName()).isEqualTo("Moyo");
        assertThat(appCustomer.isBusiness()).isTrue();

        ArgumentCaptor<TenantProfile> profile = ArgumentCaptor.forClass(TenantProfile.class);
        verify(tenants).save(profile.capture());
        assertThat(profile.getValue().getUser()).isSameAs(appCustomer);
        assertThat(profile.getValue().getBusinessName()).isEqualTo("Showtime Events");
        assertThat(profile.getValue().getBpoNumber()).isEqualTo("200123456");

        // Approval, not registration, grants the role and the product.
        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(serviceRequests).submitBusinessRegistration(eq(appCustomer), eq(Set.of("ticketing")), reason.capture());
        assertThat(reason.getValue()).contains("existing InnBucks app account", "Showtime Events", "200123456",
                "tariro@showtime.co.zw");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metadata = ArgumentCaptor.forClass(Map.class);
        verify(audit).recordRequired(eq(AuditEventType.BUSINESS_ADDED_TO_EXISTING_ACCOUNT),
                eq(appCustomer.getUserUuid().toString()), any(), eq(appCustomer.getUserUuid().toString()), any(),
                metadata.capture(), any());
        assertThat(metadata.getValue()).containsEntry("serviceRequestIds", List.of(501L))
                .containsEntry("email", "t****@showtime.co.zw")
                .containsEntry("services", List.of("ticketing"));
    }

    @Test
    void aNameGivenAtTier2_isKept() {
        appCustomer.setFirstName("Tariro");
        appCustomer.setLastName("Chikwanha");

        RegisterRequestDTO req = registration(CODE);
        req.setFirstName("Someone");
        req.setLastName("Else");
        auth.register(req);

        assertThat(appCustomer.getFirstName()).isEqualTo("Tariro");
        assertThat(appCustomer.getLastName()).isEqualTo("Chikwanha");
    }

    @Test
    void theAccountsOwnEmail_isNotADuplicate_butAnotherAccountsIs() {
        when(users.findAllByEmailIgnoreCase("tariro@showtime.co.zw")).thenReturn(List.of(appCustomer));
        assertThat(auth.register(registration(CODE)).getAddedToExistingAccount()).isTrue();

        User someoneElse = User.builder().id(40L).email("Tariro@Showtime.co.zw").build();
        when(users.findAllByEmailIgnoreCase("tariro@showtime.co.zw")).thenReturn(List.of(someoneElse));
        assertThatThrownBy(() -> auth.register(registration(CODE)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Email already registered");
    }

    @Test
    void anyOtherAccountOnThePhone_isAlreadyRegistered_asBefore() {
        User organizer = User.builder().id(33L).userUuid(UUID.randomUUID()).phoneNumber(PHONE)
                .roles(User.roleNames(User.Role.EVENT_ORGANIZER, User.Role.CUSTOMER)).active(true).approved(true)
                .password("x").build();
        onPhone(organizer);

        assertThatThrownBy(() -> auth.register(registration(CODE)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Phone number already registered");
        verify(otp, never()).verifyBusinessRegistrationCode(any(), any());
        verify(users, never()).save(any());
    }

    @Test
    void aSwitchedOffCustomer_orAStaffProfiledOne_isAlreadyRegistered() {
        appCustomer.setActive(false);
        assertThatThrownBy(() -> auth.register(registration(CODE)))
                .hasMessageContaining("Phone number already registered");

        appCustomer.setActive(true);
        when(staffProfiles.existsById(32L)).thenReturn(true);
        assertThatThrownBy(() -> auth.register(registration(CODE)))
                .hasMessageContaining("Phone number already registered");
        nothingChanged();
    }

    @Test
    void aStaffMembersPhone_cannotRegisterABusiness_evenWithNoAccountOnIt() {
        // Staff never belong to a business. Answered like any taken number, so it
        // says nothing about who is staff.
        when(users.findByPhoneNumber(PHONE)).thenReturn(Optional.empty());
        when(users.existsByPhoneNumberAndHomeCountry(PHONE, "ZW")).thenReturn(false);
        when(staffProfiles.findAllByContactPhoneIn(List.of(PHONE)))
                .thenReturn(List.of(StaffProfile.builder().userId(27L).contactPhone(PHONE).build()));

        assertThatThrownBy(() -> auth.register(registration(null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Phone number already registered");
        verify(users, never()).save(any());
    }

    @Test
    void aRegistrationAlreadyWaiting_isRefused_beforeTheCodeIsUsed() {
        when(serviceRequests.hasPending(eq(32L), anyCollection())).thenReturn(true);

        assertThatThrownBy(() -> auth.register(registration(CODE)))
                .satisfies(t -> assertThat(errorCode(t)).isEqualTo(BusinessRegistrationException.PENDING));
        verify(otp, never()).verifyBusinessRegistrationCode(any(), any());
        nothingChanged();
    }

    @Test
    void aBpoHeldByAnotherBusiness_isRefused_beforeTheCodeIsUsed() {
        when(tenants.existsByBpoNumber("200123456")).thenReturn(true);

        assertThatThrownBy(() -> auth.register(registration(CODE)))
                .hasMessageContaining("BPO number already registered");
        verify(otp, never()).verifyBusinessRegistrationCode(any(), any());
        nothingChanged();
    }

    @Test
    void reRegisteringAfterARejection_updatesTheAccountsOwnBusinessDetails() {
        TenantProfile own = TenantProfile.builder().id(9L).user(appCustomer).businessName("Old Name")
                .bpoNumber("200123456").build();
        when(tenants.findByUserId(32L)).thenReturn(Optional.of(own));
        when(tenants.existsByBpoNumber("200123456")).thenReturn(true);

        auth.register(registration(CODE));

        verify(tenants).save(own);
        assertThat(own.getBusinessName()).isEqualTo("Showtime Events");
    }

    @Test
    void aNewNumber_stillRegistersAsBefore() {
        when(users.findByPhoneNumber(PHONE)).thenReturn(Optional.empty());
        when(users.existsByPhoneNumberAndHomeCountry(PHONE, "ZW")).thenReturn(false);

        AuthResponseDTO response = auth.register(registration(null));

        assertThat(response.getAddedToExistingAccount()).isNull();
        ArgumentCaptor<User> created = ArgumentCaptor.forClass(User.class);
        verify(users).save(created.capture());
        assertThat(created.getValue()).isNotSameAs(appCustomer);
        assertThat(created.getValue().getRoles()).containsExactly("EVENT_ORGANIZER");
        assertThat(created.getValue().isActive()).isFalse();
    }

    // --- POST /auth/register/phone-code -----------------------------------------

    @Test
    void theCode_isSentToAnAppCustomersNumber() {
        businessRegistrations.sendCode("0779637565");

        verify(otp).sendBusinessRegistrationCode(PHONE);
    }

    @Test
    void noCode_isSentToANumberWithNoAccount() {
        when(users.findByPhoneNumber(PHONE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> businessRegistrations.sendCode("0779637565"))
                .satisfies(t -> assertThat(errorCode(t)).isEqualTo(BusinessRegistrationException.PHONE_CODE_NOT_NEEDED));
        verify(otp, never()).sendBusinessRegistrationCode(any());
    }

    @Test
    void noCode_isSentForAnotherKindOfAccount_aStaffPhone_orARegistrationAlreadyWaiting() {
        when(serviceRequests.hasPending(eq(32L), anyCollection())).thenReturn(true);
        assertThatThrownBy(() -> businessRegistrations.sendCode("0779637565"))
                .satisfies(t -> assertThat(errorCode(t)).isEqualTo(BusinessRegistrationException.PENDING));

        User merchant = User.builder().id(34L).phoneNumber(PHONE).roles(User.roleNames(User.Role.MERCHANT_ADMIN))
                .active(true).approved(true).build();
        onPhone(merchant);
        assertThatThrownBy(() -> businessRegistrations.sendCode("0779637565"))
                .hasMessageContaining("Phone number already registered");

        when(staffProfiles.findAllByContactPhoneIn(List.of(PHONE)))
                .thenReturn(List.of(StaffProfile.builder().userId(27L).contactPhone(PHONE).build()));
        assertThatThrownBy(() -> businessRegistrations.sendCode("0779637565"))
                .hasMessageContaining("Phone number already registered");

        verify(otp, never()).sendBusinessRegistrationCode(any());
    }

    @Test
    void theReviewersNote_staysWithinTheColumn() {
        RegisterRequestDTO req = registration(CODE);
        req.setBusinessAddress("x".repeat(2000));

        assertThat(BusinessRegistrationService.reason(req, "Showtime Events", "200123456")).hasSize(1000);
    }
}
