package com.innbucks.userservice.service;

import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.RefreshTokenRepository;
import com.innbucks.userservice.repository.RoleRepository;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.security.TokenVersionPublisher;
import com.innbucks.userservice.testsupport.BuiltInRoleRows;
import com.innbucks.userservice.testsupport.InMemoryTokenVersionBumper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Staff reset their password by EMAIL only. By phone, both steps are no-ops for
 * an account holding a staff role — the request sends nothing, the consume step
 * answers "Invalid or expired code" before the code is looked at — with exactly
 * what an unknown number gets, so the answer does not reveal the account.
 *
 * <p>A phone on a staff account is a takeover path: whoever holds the number (a
 * SIM swap, or a squatter's number left on a legacy account) could otherwise set
 * the password of an account with platform-wide authority.
 */
class PhoneResetNoOpForStaffTest {

    private static final String PHONE = "+263771234567";
    private static final String EMAIL = "tariro.moyo@innbucks.co.zw";

    private OtpService otp;
    private UserRepository users;
    private PasswordEncoder encoder;
    private PasswordResetService service;

    @BeforeEach
    void setUp() {
        otp = mock(OtpService.class);
        users = mock(UserRepository.class);
        encoder = mock(PasswordEncoder.class);
        RoleRepository roles = mock(RoleRepository.class);
        BuiltInRoleRows.stub(roles,
                BuiltInRoleRows.custom("ACCOUNT_AUDITOR", "users:read"),
                BuiltInRoleRows.custom("SHOP_VIEWER", "shop-staff:read"));
        service = new PasswordResetService(otp, users, encoder, mock(RefreshTokenRepository.class),
                mock(AuditService.class), mock(ApplicationEventPublisher.class),
                new InMemoryTokenVersionBumper(mock(TokenVersionPublisher.class)),
                new RoleGrantGuard(users, roles), org.mockito.Mockito.mock(com.innbucks.userservice.service.StaffEligibility.class));
    }

    private User account(String... roles) {
        User u = User.builder().id(9L).email(EMAIL).phoneNumber(PHONE).password("old")
                .roles(new LinkedHashSet<>(List.of(roles))).active(true).approved(true).build();
        when(users.findByPhoneNumber(PHONE)).thenReturn(Optional.of(u));
        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(u));
        return u;
    }

    @Test
    @DisplayName("request step: no code is sent to a staff account's phone")
    void requestByPhone_staff_sendsNothing() {
        account("CALL_CENTER_AGENT");
        service.requestReset(PHONE, null);
        verifyNoInteractions(otp);
    }

    @Test
    @DisplayName("request step: a custom role holding a PLATFORM permission is staff too")
    void requestByPhone_customStaffRole_sendsNothing() {
        account("CUSTOMER", "ACCOUNT_AUDITOR");
        service.requestReset(PHONE, null);
        verifyNoInteractions(otp);
    }

    @Test
    @DisplayName("request step: the same staff account resets by EMAIL as before")
    void requestByEmail_staff_isUnchanged() {
        account("PRODUCT_MANAGER");
        service.requestReset(null, EMAIL);
        verify(otp).sendPasswordResetOtpToEmail(EMAIL);
        verify(otp, never()).sendPasswordResetOtpToPhone(anyString());
    }

    @Test
    @DisplayName("request step: business and customer accounts still reset by phone")
    void requestByPhone_nonStaff_isUnchanged() {
        account("MERCHANT_ADMIN", "SHOP_VIEWER");
        service.requestReset(PHONE, null);
        verify(otp).sendPasswordResetOtpToPhone(PHONE);
    }

    @Test
    @DisplayName("consume step: a staff account by phone is 'Invalid or expired code', and the code is never read")
    void consumeByPhone_staff_isRefused() {
        User staff = account("FRAUD_DESK");
        // Even a code that WOULD verify — OTP rows carry no purpose, so one sent
        // to the same number by another flow must not work here.
        when(otp.verifyPasswordResetOtp(PHONE, "123456")).thenReturn(true);

        assertThatThrownBy(() -> service.resetPassword(PHONE, null, "123456",
                "new-pass-123", "new-pass-123", AuditContext.none()))
                .isInstanceOf(AuthService.PasswordChangeException.class)
                .hasMessage("Invalid or expired code");

        verify(otp, never()).verifyPasswordResetOtp(anyString(), anyString());
        verify(users, never()).save(any());
        assertThat(staff.getPassword()).isEqualTo("old");
    }

    @Test
    @DisplayName("consume step: the same staff account by EMAIL resets as before")
    void consumeByEmail_staff_isUnchanged() {
        User staff = account("CALL_CENTER_SUPERVISOR");
        when(otp.verifyPasswordResetOtp(EMAIL, "123456")).thenReturn(true);
        when(encoder.encode("new-pass-123")).thenReturn("hashed");

        service.resetPassword(null, EMAIL, "123456", "new-pass-123", "new-pass-123", AuditContext.none());

        assertThat(staff.getPassword()).isEqualTo("hashed");
    }

    @Test
    @DisplayName("consume step: a customer by phone resets as before")
    void consumeByPhone_customer_isUnchanged() {
        User customer = account("CUSTOMER");
        when(otp.verifyPasswordResetOtp(PHONE, "123456")).thenReturn(true);
        when(encoder.encode("new-pass-123")).thenReturn("hashed");

        service.resetPassword(PHONE, null, "123456", "new-pass-123", "new-pass-123", AuditContext.none());

        assertThat(customer.getPassword()).isEqualTo("hashed");
    }
}
