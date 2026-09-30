package com.innbucks.userservice.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The consume step (V44): an INVITED staff account answers "Invalid or expired
 * code" for ANY identifier, before the code is even looked at — OTP rows carry
 * no purpose, so a code obtained through {@code /auth/otp/request} for the same
 * key must not set a password here (the shared-OTP-row path).
 */
class InvitedStaffOtpResetRefusedTest extends ForgotPasswordInvitedStaffNoOpTest {

    @Test
    @DisplayName("INVITED: a valid code by email or phone is refused before it is checked")
    void refusedByAnyIdentifier() {
        when(otp.verifyPasswordResetOtp(anyString(), anyString())).thenReturn(true);
        String before = account.getPassword();
        assertThatThrownBy(() -> service.resetPassword(null, EMAIL, "123456", "New-Pass-99", "New-Pass-99",
                AuditContext.none()))
                .hasMessage("Invalid or expired code");
        assertThatThrownBy(() -> service.resetPassword(PHONE, null, "123456", "New-Pass-99", "New-Pass-99",
                AuditContext.none()))
                .hasMessage("Invalid or expired code");
        verify(otp, never()).verifyPasswordResetOtp(anyString(), anyString());
        assertThat(account.getPassword()).isEqualTo(before);
    }

    @Test
    @DisplayName("a profiled account after accept: by phone still refused, by email the ordinary reset")
    void profiledByPhoneRefused() {
        h.profileRows.get(account.getId()).setInviteAcceptedAt(LocalDateTime.now(ZoneOffset.UTC));
        when(otp.verifyPasswordResetOtp(anyString(), anyString())).thenReturn(true);
        assertThatThrownBy(() -> service.resetPassword(PHONE, null, "123456", "New-Pass-99", "New-Pass-99",
                AuditContext.none()))
                .hasMessage("Invalid or expired code");
        service.resetPassword(null, EMAIL, "123456", "New-Pass-99", "New-Pass-99", AuditContext.none());
        assertThat(account.getPassword()).isEqualTo("{x}New-Pass-99");
        verify(otp).verifyPasswordResetOtp(any(), any());
    }
}
