package com.innbucks.userservice.service;

import com.innbucks.userservice.client.WhatsAppNotificationClient;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.exception.NotFoundException;
import com.innbucks.userservice.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UserAdminServiceTest {

    @Test
    void firstActivation_approvesAssignsDefaultPasswordAndNotifies() {
        UserRepository userRepo = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        WhatsAppNotificationClient whatsApp = mock(WhatsAppNotificationClient.class);
        // As created by /auth/register: inactive, unapproved, placeholder password.
        User user = User.builder().id(1L).email("a@b.com").phoneNumber("+263771234567")
                .password("placeholder").active(false).approved(false).build();
        when(userRepo.findById(1L)).thenReturn(Optional.of(user));
        when(userRepo.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(encoder.encode("#Pass123")).thenReturn("encoded-default");

        User result = new UserAdminService(userRepo, encoder, whatsApp).setActive(1L, true);

        assertTrue(result.isActive());
        assertTrue(result.isApproved());
        assertTrue(result.isMustChangePassword());
        assertEquals("encoded-default", result.getPassword());
        verify(encoder).encode("#Pass123");
        // The first-time password is WhatsApp'd to the approved user.
        verify(whatsApp).sendCustomNotification(eq("+263771234567"), contains("#Pass123"));
    }

    @Test
    void reactivation_doesNotResetPasswordOrNotify() {
        UserRepository userRepo = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        WhatsAppNotificationClient whatsApp = mock(WhatsAppNotificationClient.class);
        // Already approved, later deactivated; user has since chosen their own password.
        User user = User.builder().id(2L).email("c@d.com").password("user-chosen")
                .active(false).approved(true).mustChangePassword(false).build();
        when(userRepo.findById(2L)).thenReturn(Optional.of(user));
        when(userRepo.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User result = new UserAdminService(userRepo, encoder, whatsApp).setActive(2L, true);

        assertTrue(result.isActive());
        assertEquals("user-chosen", result.getPassword());
        assertFalse(result.isMustChangePassword());
        verify(encoder, never()).encode(any());
        // Re-activation is not a first approval — no password is re-sent.
        verifyNoInteractions(whatsApp);
    }

    @Test
    void noOp_whenAlreadyActiveAndApproved() {
        UserRepository userRepo = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        WhatsAppNotificationClient whatsApp = mock(WhatsAppNotificationClient.class);
        User user = User.builder().id(3L).active(true).approved(true).password("pw").build();
        when(userRepo.findById(3L)).thenReturn(Optional.of(user));

        new UserAdminService(userRepo, encoder, whatsApp).setActive(3L, true);

        verify(userRepo, never()).save(any());
        verify(encoder, never()).encode(any());
        verifyNoInteractions(whatsApp);
    }

    @Test
    void deactivation_ofApprovedUser_leavesPasswordUntouched() {
        UserRepository userRepo = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        WhatsAppNotificationClient whatsApp = mock(WhatsAppNotificationClient.class);
        User user = User.builder().id(4L).active(true).approved(true).password("pw").build();
        when(userRepo.findById(4L)).thenReturn(Optional.of(user));
        when(userRepo.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User result = new UserAdminService(userRepo, encoder, whatsApp).setActive(4L, false);

        assertFalse(result.isActive());
        assertEquals("pw", result.getPassword());
        verify(encoder, never()).encode(any());
        verifyNoInteractions(whatsApp);
    }

    @Test
    void throwsNotFound_whenUserMissing() {
        UserRepository userRepo = mock(UserRepository.class);
        when(userRepo.findById(99L)).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class,
                () -> new UserAdminService(userRepo, mock(PasswordEncoder.class),
                        mock(WhatsAppNotificationClient.class)).setActive(99L, true));
    }
}
