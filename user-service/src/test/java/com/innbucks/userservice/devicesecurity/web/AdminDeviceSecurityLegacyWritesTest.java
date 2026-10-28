package com.innbucks.userservice.devicesecurity.web;

import com.innbucks.userservice.devicesecurity.DeviceSecurityException;
import com.innbucks.userservice.devicesecurity.DeviceSupportService;
import com.innbucks.userservice.devicesecurity.dto.SupportDTOs.ActionResult;
import com.innbucks.userservice.devicesecurity.dto.SupportDTOs.BanRequest;
import com.innbucks.userservice.devicesecurity.dto.SupportDTOs.BlockRequest;
import com.innbucks.userservice.devicesecurity.dto.SupportDTOs.FraudFlagRequest;
import com.innbucks.userservice.devicesecurity.dto.SupportDTOs.NoteRequest;
import com.innbucks.userservice.support.DeviceSecurityReadAccess;
import com.innbucks.userservice.support.SupportProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The seven unbound device-security writes retire behind
 * {@code support.legacy-device-writes-enabled}: on (the default) they work as
 * before; off, every one answers {@code 410 use_support_endpoints} before the
 * service is touched, so nothing can reach a customer's phones without a lookup.
 */
class AdminDeviceSecurityLegacyWritesTest {

    private static final UUID DEVICE = UUID.fromString("0f8d2c3a-5b6e-4f71-8a9b-0c1d2e3f4a5b");

    private DeviceSupportService service;
    private DeviceSecurityReadAccess access;
    private SupportProperties props;
    private AdminDeviceSecurityController controller;
    private final Authentication auth = new UsernamePasswordAuthenticationToken("agent.one@innbucks.co.zw", null,
            List.of());
    private final MockHttpServletRequest request = new MockHttpServletRequest();

    @BeforeEach
    void setUp() {
        service = mock(DeviceSupportService.class);
        props = new SupportProperties();
        access = mock(DeviceSecurityReadAccess.class);
        controller = new AdminDeviceSecurityController(service, access, props);
        when(service.block(any(), any(), any(), any())).thenReturn(new ActionResult(null, "Blocked."));
    }

    private List<Executable> everyWrite() {
        NoteRequest note = new NoteRequest("Caller verified.");
        return List.of(
                () -> controller.block(DEVICE, new BlockRequest("UNTIL_UNLOCKED", null, "Caller verified."), auth,
                        request),
                () -> controller.ban(DEVICE, new BanRequest("FRAUD_SUSPECTED", "Fraud desk."), auth, request),
                () -> controller.unlock(DEVICE, note, auth, request),
                () -> controller.revoke(DEVICE, note, auth, request),
                () -> controller.resetTrust(DEVICE, note, auth, request),
                () -> controller.fraudFlag("+263771234567", new FraudFlagRequest(true, "SIM swap."), auth, request),
                () -> controller.cancelCodes("+263771234567", note, auth, request));
    }

    @Test
    @DisplayName("the default keeps the old writes working, so the console is not broken before it moves")
    void onByDefault() {
        assertThat(props.isLegacyDeviceWritesEnabled()).isTrue();
        assertThat(controller.block(DEVICE, new BlockRequest("UNTIL_UNLOCKED", null, "Caller verified."), auth,
                request).getStatusCode().value()).isEqualTo(200);
        verify(service).block(any(), any(), any(), any());
    }

    @Test
    @DisplayName("switched off, every one of the seven writes is 410 use_support_endpoints and touches nothing")
    void offRetiresEveryWrite() {
        props.setLegacyDeviceWritesEnabled(false);
        for (Executable write : everyWrite()) {
            assertThatThrownBy(write::execute).isInstanceOfSatisfying(DeviceSecurityException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.GONE);
                assertThat(e.getErrorCode()).isEqualTo("use_support_endpoints");
                assertThat(e.getData()).containsEntry("use", "/admin/support/innbucks-app");
            });
        }
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("switched off, the reads are untouched by the flag")
    void readsStay() {
        props.setLegacyDeviceWritesEnabled(false);
        assertThat(controller.overview("+263771234567", auth, request).getStatusCode().value()).isEqualTo(200);
        verify(access).read(any(), any(), any(), any(), any(), any());
    }
}
