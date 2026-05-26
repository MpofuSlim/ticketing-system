package com.innbucks.userservice.service;

import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.exception.NotFoundException;
import com.innbucks.userservice.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class UserAdminServiceTest {

    @Test
    void firstActivation_approvesAndAssignsDefaultPassword() {
        UserRepository userRepo = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        // As created by /auth/register: inactive, unapproved, placeholder password.
        User user = User.builder().id(1L).email("a@b.com").password("placeholder")
                .active(false).approved(false).build();
        when(userRepo.findById(1L)).thenReturn(Optional.of(user));
        when(userRepo.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(encoder.encode("#Pass123")).thenReturn("encoded-default");

        User result = new UserAdminService(userRepo, encoder).setActive(1L, true);

        assertTrue(result.isActive());
        assertTrue(result.isApproved());
        assertTrue(result.isMustChangePassword());
        assertEquals("encoded-default", result.getPassword());
        verify(encoder).encode("#Pass123");
    }

    @Test
    void reactivation_doesNotResetPassword() {
        UserRepository userRepo = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        // Already approved, later deactivated; user has since chosen their own password.
        User user = User.builder().id(2L).email("c@d.com").password("user-chosen")
                .active(false).approved(true).mustChangePassword(false).build();
        when(userRepo.findById(2L)).thenReturn(Optional.of(user));
        when(userRepo.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User result = new UserAdminService(userRepo, encoder).setActive(2L, true);

        assertTrue(result.isActive());
        assertEquals("user-chosen", result.getPassword());
        assertFalse(result.isMustChangePassword());
        verify(encoder, never()).encode(any());
    }

    @Test
    void noOp_whenAlreadyActiveAndApproved() {
        UserRepository userRepo = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        User user = User.builder().id(3L).active(true).approved(true).password("pw").build();
        when(userRepo.findById(3L)).thenReturn(Optional.of(user));

        new UserAdminService(userRepo, encoder).setActive(3L, true);

        verify(userRepo, never()).save(any());
        verify(encoder, never()).encode(any());
    }

    @Test
    void deactivation_ofApprovedUser_leavesPasswordUntouched() {
        UserRepository userRepo = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        User user = User.builder().id(4L).active(true).approved(true).password("pw").build();
        when(userRepo.findById(4L)).thenReturn(Optional.of(user));
        when(userRepo.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User result = new UserAdminService(userRepo, encoder).setActive(4L, false);

        assertFalse(result.isActive());
        assertEquals("pw", result.getPassword());
        verify(encoder, never()).encode(any());
    }

    @Test
    void throwsNotFound_whenUserMissing() {
        UserRepository userRepo = mock(UserRepository.class);
        when(userRepo.findById(99L)).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class,
                () -> new UserAdminService(userRepo, mock(PasswordEncoder.class)).setActive(99L, true));
    }
}
