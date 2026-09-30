package com.innbucks.userservice.service;

import com.innbucks.userservice.entity.StaffProfile;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.RefreshTokenRepository;
import com.innbucks.userservice.security.TokenVersionPublisher;
import com.innbucks.userservice.testsupport.InMemoryTokenVersionBumper;
import com.innbucks.userservice.testsupport.StaffDispatchHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

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
 * Forgot-password (V44): an INVITED staff account is sent nothing by ANY
 * identifier, and a staff-PROFILED account nothing by phone — with the same
 * generic 200 an unknown identifier gets. Until the invite is redeemed nobody has
 * chosen the account's password, and a reset would set one without proving the
 * mailbox.
 */
class ForgotPasswordInvitedStaffNoOpTest {

    static final String EMAIL = "tariro.moyo@innbucks.co.zw";
    static final String PHONE = "+263771234567";

    StaffDispatchHarness h;
    OtpService otp;
    PasswordResetService service;
    User account;

    @BeforeEach
    void setUp() {
        h = new StaffDispatchHarness();
        otp = mock(OtpService.class);
        service = new PasswordResetService(otp, h.users, h.passwordEncoder, mock(RefreshTokenRepository.class),
                h.audit, mock(ApplicationEventPublisher.class),
                new InMemoryTokenVersionBumper(mock(TokenVersionPublisher.class)), h.guard, h.eligibility);
        account = h.account(EMAIL, "CALL_CENTER_AGENT");
        account.setPhoneNumber(PHONE);
        h.profileRows.put(account.getId(), StaffProfile.builder().userId(account.getId())
                .createdAt(LocalDateTime.now(ZoneOffset.UTC)).build());
    }

    @Test
    @DisplayName("INVITED: nothing is sent, by email or by phone")
    void invitedSendsNothing() {
        service.requestReset(null, EMAIL);
        service.requestReset(PHONE, null);
        verifyNoInteractions(otp);
    }

    @Test
    @DisplayName("once accepted: email resets work again; phone stays a no-op for a profiled account")
    void acceptedByEmailOnly() {
        h.profileRows.get(account.getId()).setInviteAcceptedAt(LocalDateTime.now(ZoneOffset.UTC));
        account.getRoles().clear();
        account.getRoles().add("CUSTOMER"); // not a staff ROLE — the profile alone must keep phone off
        service.requestReset(PHONE, null);
        verify(otp, never()).sendPasswordResetOtpToPhone(anyString());
        service.requestReset(null, EMAIL);
        verify(otp).sendPasswordResetOtpToEmail(EMAIL);
    }
}
