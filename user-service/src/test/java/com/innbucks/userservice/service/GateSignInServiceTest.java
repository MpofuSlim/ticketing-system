package com.innbucks.userservice.service;

import com.innbucks.userservice.cells.CellAffinityChecker;
import com.innbucks.userservice.cells.WrongCellException;
import com.innbucks.userservice.dto.AuthResponseDTO;
import com.innbucks.userservice.entity.CustomerProfile;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.exception.GateSignInException;
import com.innbucks.userservice.integration.EventServiceClient;
import com.innbucks.userservice.repository.CustomerProfileRepository;
import com.innbucks.userservice.repository.UserRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The gate sign-in (V57): who is sent a code, what every caller is told, and
 * which account the session names.
 */
class GateSignInServiceTest {

    private static final String PHONE = "+263771234567";

    private EventServiceClient eventService;
    private OtpService otpService;
    private UserRepository userRepository;
    private CustomerProfileRepository profiles;
    private AuthService authService;
    private AuditService auditService;
    private SimpleMeterRegistry meters;
    private GateSignInService service;

    @BeforeEach
    void setUp() {
        eventService = mock(EventServiceClient.class);
        otpService = mock(OtpService.class);
        userRepository = mock(UserRepository.class);
        profiles = mock(CustomerProfileRepository.class);
        authService = mock(AuthService.class);
        auditService = mock(AuditService.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(encoder.encode(anyString())).thenReturn("{argon2}unusable");
        meters = new SimpleMeterRegistry();
        service = new GateSignInService(true, eventService, otpService, userRepository, profiles, encoder,
                authService, auditService, new CellAffinityChecker("ZW", mock(com.innbucks.userservice.cells.CellRegistry.class)), "ZW", meters);
    }

    private double count(String metric, String outcome) {
        return meters.counter(metric, "outcome", outcome).count();
    }

    // ---- POST /auth/gate/code -------------------------------------------

    @Test
    @DisplayName("a number on a gate team is sent a code, in any spelling")
    void code_onATeam_isSent() {
        when(eventService.onAGateTeam(PHONE)).thenReturn(Optional.of(true));

        service.requestCode("0771 234 567");

        verify(otpService).sendGateSignInCode(PHONE);
        verify(otpService, never()).countGateSignInRequest(anyString());
        assertThat(count(GateSignInService.CODE_METRIC, "sent")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("a number on no team is sent nothing, but its quota is spent like a real send")
    void code_notOnATeam_spendsTheQuotaOnly() {
        when(eventService.onAGateTeam(PHONE)).thenReturn(Optional.of(false));

        service.requestCode(PHONE);

        verify(otpService, never()).sendGateSignInCode(anyString());
        verify(otpService).countGateSignInRequest(PHONE);
        assertThat(count(GateSignInService.CODE_METRIC, "not_on_team")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("the quota refusal is the same exception for a team number and for any other")
    void code_quotaRefusal_isTheSameEitherWay() {
        when(eventService.onAGateTeam(PHONE)).thenReturn(Optional.of(false));
        doThrow(new OtpService.OtpRateLimitException("Too many OTP requests. Try again in 30 minute(s)."))
                .when(otpService).countGateSignInRequest(PHONE);
        assertThatThrownBy(() -> service.requestCode(PHONE)).isInstanceOf(OtpService.OtpRateLimitException.class);

        when(eventService.onAGateTeam(PHONE)).thenReturn(Optional.of(true));
        doThrow(new OtpService.OtpRateLimitException("Too many OTP requests. Try again in 30 minute(s)."))
                .when(otpService).sendGateSignInCode(PHONE);
        assertThatThrownBy(() -> service.requestCode(PHONE)).isInstanceOf(OtpService.OtpRateLimitException.class);
    }

    @Test
    @DisplayName("when event-service cannot answer: 503, nothing sent and nothing counted")
    void code_eventServiceDown_is503() {
        when(eventService.onAGateTeam(PHONE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requestCode(PHONE))
                .isInstanceOf(GateSignInException.class)
                .satisfies(e -> {
                    GateSignInException ex = (GateSignInException) e;
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(ex.getErrorCode()).isEqualTo("gate_sign_in_unavailable");
                });
        verifyNoInteractions(otpService);
        assertThat(count(GateSignInService.CODE_METRIC, "unavailable")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("not a phone number: 400 invalid_phone, and event-service is never asked")
    void code_invalidPhone_is400() {
        assertThatThrownBy(() -> service.requestCode("not-a-number"))
                .isInstanceOf(GateSignInException.class)
                .extracting(e -> ((GateSignInException) e).getErrorCode())
                .isEqualTo("invalid_phone");
        verifyNoInteractions(eventService, otpService);
    }

    @Test
    @DisplayName("another region's number is the wrong-cell answer, before any team question")
    void code_foreignNumber_isWrongCell() {
        assertThatThrownBy(() -> service.requestCode("+27821234567")).isInstanceOf(WrongCellException.class);
        verifyNoInteractions(eventService, otpService);
    }

    @Test
    @DisplayName("switched off: 404 on both calls, nothing asked")
    void disabled_is404() {
        GateSignInService off = new GateSignInService(false, eventService, otpService, userRepository, profiles,
                mock(PasswordEncoder.class), authService, auditService, new CellAffinityChecker("ZW", mock(com.innbucks.userservice.cells.CellRegistry.class)), "ZW",
                new SimpleMeterRegistry());

        assertThatThrownBy(() -> off.requestCode(PHONE))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode().value()).isEqualTo(404);
        assertThatThrownBy(() -> off.signIn(PHONE, "123456", "d", AuditContext.none()))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(eventService, otpService, authService);
    }

    // ---- POST /auth/gate/sign-in ----------------------------------------

    @Test
    @DisplayName("a wrong (or never sent) code is 400 code_invalid, audited, and mints nothing")
    void signIn_wrongCode_isRefused() {
        when(otpService.verifyGateSignInCode(PHONE, "000000")).thenReturn(false);

        assertThatThrownBy(() -> service.signIn(PHONE, "000000", "device-1", AuditContext.none()))
                .isInstanceOf(GateSignInException.class)
                .satisfies(e -> {
                    GateSignInException ex = (GateSignInException) e;
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(ex.getErrorCode()).isEqualTo("code_invalid");
                });
        verify(auditService).recordFailure(eq(AuditEventType.AUTH_GATE_SIGN_IN_REJECTED), eq(PHONE), anyString(),
                eq(PHONE), anyString(), eq("wrong_code"), any(), any());
        verifyNoInteractions(authService, userRepository);
        assertThat(count(GateSignInService.SIGN_IN_METRIC, "rejected")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("the right code signs in the account already on the number, whatever it holds")
    void signIn_existingAccount_getsAGateSession() {
        User organizer = User.builder().id(5L).userUuid(UUID.randomUUID()).phoneNumber(PHONE)
                .roles(User.roleNames(User.Role.EVENT_ORGANIZER)).active(true).approved(true).build();
        when(otpService.verifyGateSignInCode(PHONE, "482913")).thenReturn(true);
        when(userRepository.findByPhoneNumber(PHONE)).thenReturn(Optional.of(organizer));
        AuthResponseDTO minted = AuthResponseDTO.builder().token("t").build();
        when(authService.issueGateScannerToken(organizer, "device-1")).thenReturn(minted);

        assertThat(service.signIn("0771234567", "482913", "device-1", AuditContext.none())).isSameAs(minted);

        // Never a customer session, never a password-login session.
        verify(authService, never()).issuePhoneProofToken(any(), any());
        verify(authService, never()).issueToken(any(), any());
        // The account is used as it is: no role added, no profile written.
        assertThat(organizer.getRoles()).containsExactly("EVENT_ORGANIZER");
        verify(userRepository, never()).save(any());
        verify(auditService).recordSuccess(eq(AuditEventType.AUTH_GATE_SIGN_IN_SUCCESS), eq(PHONE), anyString(),
                eq("5"), anyString(), eq(Map.of("newAccount", false)), any());
    }

    @Test
    @DisplayName("a deactivated account is the same code_invalid answer, and mints nothing")
    void signIn_inactiveAccount_isRefused() {
        User inactive = User.builder().id(6L).phoneNumber(PHONE)
                .roles(User.roleNames(User.Role.CUSTOMER)).active(false).approved(true).build();
        when(otpService.verifyGateSignInCode(PHONE, "482913")).thenReturn(true);
        when(userRepository.findByPhoneNumber(PHONE)).thenReturn(Optional.of(inactive));

        assertThatThrownBy(() -> service.signIn(PHONE, "482913", "device-1", AuditContext.none()))
                .isInstanceOf(GateSignInException.class)
                .extracting(e -> ((GateSignInException) e).getErrorCode())
                .isEqualTo("code_invalid");
        verify(authService, never()).issueGateScannerToken(any(), any());
        verify(auditService).recordFailure(eq(AuditEventType.AUTH_GATE_SIGN_IN_REJECTED), eq(PHONE), anyString(),
                eq(PHONE), anyString(), eq("account_inactive"), any(), any());
    }

    @Test
    @DisplayName("a number with no account gets a tier-1 customer account, the one the InnBucks app finds later")
    void signIn_newNumber_createsACustomerAccount() {
        when(otpService.verifyGateSignInCode(PHONE, "482913")).thenReturn(true);
        when(userRepository.findByPhoneNumber(PHONE)).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(9L);
            return u;
        });
        when(authService.issueGateScannerToken(any(User.class), eq("device-1")))
                .thenReturn(AuthResponseDTO.builder().token("t").build());

        service.signIn(PHONE, "482913", "device-1", AuditContext.none());

        ArgumentCaptor<User> created = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(created.capture());
        assertThat(created.getValue().getPhoneNumber()).isEqualTo(PHONE);
        assertThat(created.getValue().getRoles()).containsExactly("CUSTOMER");
        assertThat(created.getValue().isActive()).isTrue();
        assertThat(created.getValue().isApproved()).isTrue();
        assertThat(created.getValue().getHomeCountry()).isEqualTo("ZW");
        ArgumentCaptor<CustomerProfile> profile = ArgumentCaptor.forClass(CustomerProfile.class);
        verify(profiles).save(profile.capture());
        assertThat(profile.getValue().getRegistrationTier()).isEqualTo(1);
        assertThat(profile.getValue().isPhoneVerified()).isTrue();
        verify(authService).issueGateScannerToken(created.getValue(), "device-1");
        verify(auditService).recordSuccess(eq(AuditEventType.AUTH_GATE_SIGN_IN_SUCCESS), eq(PHONE), anyString(),
                eq("9"), anyString(), eq(Map.of("newAccount", true)), any());
    }

    @Test
    @DisplayName("the sign-in never asks event-service: authority to scan is checked at every scan")
    void signIn_doesNotAskEventService() {
        when(otpService.verifyGateSignInCode(PHONE, "000000")).thenReturn(false);

        assertThatThrownBy(() -> service.signIn(PHONE, "000000", "d", AuditContext.none()))
                .isInstanceOf(GateSignInException.class);
        verifyNoInteractions(eventService);
    }
}
