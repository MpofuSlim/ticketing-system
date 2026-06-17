package com.innbucks.userservice.service;

import com.innbucks.userservice.client.EmailNotificationClient;
import com.innbucks.userservice.client.SmsNotificationClient;
import com.innbucks.userservice.dto.CreateTeamMemberDTO;
import com.innbucks.userservice.dto.UserResponseDTO;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.RefreshTokenRepository;
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

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TeamMemberService}. Pins the four invariants this
 * service exists to enforce:
 *
 * <ol>
 *   <li>Only an EVENT_ORGANIZER may manage team members.</li>
 *   <li>A team member is owned by exactly one organizer — the one whose
 *       JWT created them. Cross-organizer reads/writes return 404.</li>
 *   <li>Disable is soft (active=false + tokenVersion++ + refresh-token revoke),
 *       never a DELETE — the audit FK on booking_items must never orphan.</li>
 *   <li>Enable / disable are idempotent.</li>
 * </ol>
 *
 * <p>Pure Mockito, no Spring context. Deployment country is injected via
 * reflection because it's an {@code @Value} field, not a constructor arg.
 */
@ExtendWith(MockitoExtension.class)
class TeamMemberServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private EmailNotificationClient emailNotificationClient;
    @Mock private SmsNotificationClient smsNotificationClient;

    @InjectMocks private TeamMemberService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "deploymentCountry", "ZW");
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private User organizer(UUID userUuid) {
        return User.builder()
                .id(1L)
                .userUuid(userUuid)
                .email("organizer@harare-arena.co.zw")
                .firstName("Olive")
                .lastName("Mutsa")
                .phoneNumber("+263771234500")
                .roles(EnumSet.of(User.Role.EVENT_ORGANIZER))
                .active(true)
                .build();
    }

    private User teamMember(UUID userUuid, UUID organizerUuid) {
        return User.builder()
                .id(99L)
                .userUuid(userUuid)
                .email("tariro@harare-arena.co.zw")
                .firstName("Tariro")
                .lastName("Chikomo")
                .phoneNumber("+263773456789")
                .roles(EnumSet.of(User.Role.TEAM_MEMBER))
                .createdByOrganizerUuid(organizerUuid)
                .tokenVersion(3L)
                .active(true)
                .build();
    }

    private void authenticateAs(User caller) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(caller.getEmail(), null));
        when(userRepository.findByEmail(caller.getEmail())).thenReturn(Optional.of(caller));
    }

    private CreateTeamMemberDTO createDto() {
        CreateTeamMemberDTO dto = new CreateTeamMemberDTO();
        dto.setFirstName("Tariro");
        dto.setLastName("Chikomo");
        dto.setEmail("tariro@harare-arena.co.zw");
        dto.setPhoneNumber("+263773456789");
        return dto;
    }

    // ----- create -----

    @Test
    void create_stampsOrganizerUuidAndTeamMemberRole() {
        UUID organizerUuid = UUID.randomUUID();
        User organizer = organizer(organizerUuid);
        authenticateAs(organizer);
        when(userRepository.existsByEmail("tariro@harare-arena.co.zw")).thenReturn(false);
        when(userRepository.existsByPhoneNumberAndHomeCountry("+263773456789", "ZW")).thenReturn(false);
        when(passwordEncoder.encode(any())).thenReturn("HASHED");

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        when(userRepository.save(saved.capture())).thenAnswer(inv -> {
            User u = saved.getValue();
            u.setId(99L);
            return u;
        });

        UserResponseDTO result = service.createTeamMember(createDto());

        assertThat(result.getRoles()).containsExactly("TEAM_MEMBER");
        assertThat(result.getCreatedByOrganizerUuid()).isEqualTo(organizerUuid);
        assertThat(result.isActive()).isTrue();
        assertThat(saved.getValue().getRoles()).containsExactly(User.Role.TEAM_MEMBER);
        assertThat(saved.getValue().getCreatedByOrganizerUuid()).isEqualTo(organizerUuid);
        assertThat(saved.getValue().getHomeCountry()).isEqualTo("ZW");
    }

    @Test
    void create_rejectsDuplicateEmail() {
        authenticateAs(organizer(UUID.randomUUID()));
        when(userRepository.existsByEmail("tariro@harare-arena.co.zw")).thenReturn(true);

        assertThatThrownBy(() -> service.createTeamMember(createDto()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Email already registered");
        verify(userRepository, never()).save(any());
    }

    @Test
    void create_rejectsNonOrganizerCaller() {
        // A SHOP_ADMIN with a logged-in session must not be able to create
        // team members even though they have a token — the role gate is the
        // backstop behind the controller's @PreAuthorize.
        User shopAdmin = User.builder()
                .id(2L).userUuid(UUID.randomUUID())
                .email("shopadmin@example.com").firstName("S").lastName("A")
                .phoneNumber("+263771111111")
                .roles(EnumSet.of(User.Role.SHOP_ADMIN))
                .active(true).build();
        authenticateAs(shopAdmin);

        assertThatThrownBy(() -> service.createTeamMember(createDto()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    // ----- list / get -----

    @Test
    void list_returnsOnlyMembersOwnedByCaller() {
        UUID organizerUuid = UUID.randomUUID();
        authenticateAs(organizer(organizerUuid));
        when(userRepository.findByCreatedByOrganizerUuid(organizerUuid))
                .thenReturn(List.of(teamMember(UUID.randomUUID(), organizerUuid)));

        List<UserResponseDTO> result = service.listMyTeam();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getCreatedByOrganizerUuid()).isEqualTo(organizerUuid);
    }

    @Test
    void get_returns404WhenMemberBelongsToDifferentOrganizer() {
        UUID callerUuid = UUID.randomUUID();
        UUID otherOrganizerUuid = UUID.randomUUID();
        UUID memberUuid = UUID.randomUUID();
        authenticateAs(organizer(callerUuid));
        when(userRepository.findByUserUuid(memberUuid))
                .thenReturn(Optional.of(teamMember(memberUuid, otherOrganizerUuid)));

        assertThatThrownBy(() -> service.getMyTeamMember(memberUuid))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void get_returns404WhenUuidResolvesToANonTeamMember() {
        // A correctly-targeted UUID that happens to point at a CUSTOMER or
        // another role must NOT leak through the team-member API.
        UUID callerUuid = UUID.randomUUID();
        UUID someoneUuid = UUID.randomUUID();
        authenticateAs(organizer(callerUuid));
        User customer = User.builder()
                .id(7L).userUuid(someoneUuid)
                .email("c@example.com").firstName("C").lastName("X")
                .phoneNumber("+263778888888")
                .roles(EnumSet.of(User.Role.CUSTOMER))
                .active(true).build();
        when(userRepository.findByUserUuid(someoneUuid)).thenReturn(Optional.of(customer));

        assertThatThrownBy(() -> service.getMyTeamMember(someoneUuid))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    // ----- disable / enable -----

    @Test
    void disable_flipsActiveBumpsTokenVersionAndRevokesRefreshFamilies() {
        UUID organizerUuid = UUID.randomUUID();
        UUID memberUuid = UUID.randomUUID();
        User member = teamMember(memberUuid, organizerUuid);
        long versionBefore = member.getTokenVersion();
        authenticateAs(organizer(organizerUuid));
        when(userRepository.findByUserUuid(memberUuid)).thenReturn(Optional.of(member));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(refreshTokenRepository.revokeAllForUser(eq(99L), any(Instant.class))).thenReturn(2);

        UserResponseDTO result = service.disableTeamMember(memberUuid);

        assertThat(result.isActive()).isFalse();
        assertThat(member.getTokenVersion()).isEqualTo(versionBefore + 1);
        verify(refreshTokenRepository).revokeAllForUser(eq(99L), any(Instant.class));
    }

    @Test
    void disable_isIdempotentOnAnAlreadyDisabledMember() {
        UUID organizerUuid = UUID.randomUUID();
        UUID memberUuid = UUID.randomUUID();
        User member = teamMember(memberUuid, organizerUuid);
        member.setActive(false);
        long versionBefore = member.getTokenVersion();
        authenticateAs(organizer(organizerUuid));
        when(userRepository.findByUserUuid(memberUuid)).thenReturn(Optional.of(member));

        UserResponseDTO result = service.disableTeamMember(memberUuid);

        assertThat(result.isActive()).isFalse();
        assertThat(member.getTokenVersion()).isEqualTo(versionBefore);
        verify(userRepository, never()).save(any());
        verify(refreshTokenRepository, never()).revokeAllForUser(anyLong(), any(Instant.class));
    }

    @Test
    void enable_flipsActiveBackToTrueWithoutRevokingTokens() {
        UUID organizerUuid = UUID.randomUUID();
        UUID memberUuid = UUID.randomUUID();
        User member = teamMember(memberUuid, organizerUuid);
        member.setActive(false);
        long versionBefore = member.getTokenVersion();
        authenticateAs(organizer(organizerUuid));
        when(userRepository.findByUserUuid(memberUuid)).thenReturn(Optional.of(member));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        UserResponseDTO result = service.enableTeamMember(memberUuid);

        assertThat(result.isActive()).isTrue();
        // Re-enable does NOT bump tokenVersion or touch refresh tokens —
        // the member has to log in fresh because disable already invalidated
        // every prior session; we're not also throwing away NEW sessions
        // they haven't started yet.
        assertThat(member.getTokenVersion()).isEqualTo(versionBefore);
        verify(refreshTokenRepository, never()).revokeAllForUser(anyLong(), any(Instant.class));
    }
}
