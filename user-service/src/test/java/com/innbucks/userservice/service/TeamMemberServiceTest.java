package com.innbucks.userservice.service;

import com.innbucks.userservice.dto.CreateTeamMemberDTO;
import com.innbucks.userservice.dto.UserResponseDTO;
import com.innbucks.userservice.entity.TeamMemberEventAssignment;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.event.CredentialDeliveryRequested;
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
import org.springframework.context.ApplicationEventPublisher;
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
import static org.mockito.Mockito.doThrow;
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
    @Mock private com.innbucks.userservice.repository.TeamMemberEventAssignmentRepository assignmentRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private com.innbucks.userservice.security.TokenVersionPublisher tokenVersionPublisher;

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
        // A07 / CWE-613: the bumped version is mirrored to the shared Redis under
        // the member's own userUuid (== their JWT userUuid claim) so downstream
        // rejects their outstanding tokens immediately.
        verify(tokenVersionPublisher).publish(memberUuid, versionBefore + 1);
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
        // No bump -> nothing published to the shared Redis.
        verify(tokenVersionPublisher, never()).publish(any(), anyLong());
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

    @Test
    void assignEvent_savesNewAssignmentAndReturnsFullSet() {
        UUID organizerUuid = UUID.randomUUID();
        UUID memberUuid = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        authenticateAs(organizer(organizerUuid));
        when(userRepository.findByUserUuid(memberUuid))
                .thenReturn(Optional.of(teamMember(memberUuid, organizerUuid)));
        when(assignmentRepository.existsByTeamMemberUserUuidAndEventId(memberUuid, eventId)).thenReturn(false);
        when(assignmentRepository.findByTeamMemberUserUuid(memberUuid))
                .thenReturn(List.of(TeamMemberEventAssignment.builder()
                        .teamMemberUserUuid(memberUuid).eventId(eventId)
                        .assignedByOrganizerUuid(organizerUuid).build()));

        List<UUID> result = service.assignEvent(memberUuid, eventId);

        assertThat(result).containsExactly(eventId);
        verify(assignmentRepository).save(any(TeamMemberEventAssignment.class));
    }

    @Test
    void assignEvent_isIdempotent_doesNotSaveDuplicate() {
        UUID organizerUuid = UUID.randomUUID();
        UUID memberUuid = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        authenticateAs(organizer(organizerUuid));
        when(userRepository.findByUserUuid(memberUuid))
                .thenReturn(Optional.of(teamMember(memberUuid, organizerUuid)));
        when(assignmentRepository.existsByTeamMemberUserUuidAndEventId(memberUuid, eventId)).thenReturn(true);
        when(assignmentRepository.findByTeamMemberUserUuid(memberUuid))
                .thenReturn(List.of(TeamMemberEventAssignment.builder()
                        .teamMemberUserUuid(memberUuid).eventId(eventId)
                        .assignedByOrganizerUuid(organizerUuid).build()));

        service.assignEvent(memberUuid, eventId);

        verify(assignmentRepository, never()).save(any());
    }

    @Test
    void assignEvent_rejectsMemberOwnedByDifferentOrganizer() {
        UUID callerUuid = UUID.randomUUID();
        UUID otherOrganizerUuid = UUID.randomUUID();
        UUID memberUuid = UUID.randomUUID();
        authenticateAs(organizer(callerUuid));
        when(userRepository.findByUserUuid(memberUuid))
                .thenReturn(Optional.of(teamMember(memberUuid, otherOrganizerUuid)));

        assertThatThrownBy(() -> service.assignEvent(memberUuid, UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
        verify(assignmentRepository, never()).save(any());
    }

    @Test
    void unassignEvent_deletesAndReturnsRemaining() {
        UUID organizerUuid = UUID.randomUUID();
        UUID memberUuid = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        authenticateAs(organizer(organizerUuid));
        when(userRepository.findByUserUuid(memberUuid))
                .thenReturn(Optional.of(teamMember(memberUuid, organizerUuid)));
        when(assignmentRepository.deleteByTeamMemberUserUuidAndEventId(memberUuid, eventId)).thenReturn(1L);
        when(assignmentRepository.findByTeamMemberUserUuid(memberUuid)).thenReturn(List.of());

        List<UUID> result = service.unassignEvent(memberUuid, eventId);

        assertThat(result).isEmpty();
        verify(assignmentRepository).deleteByTeamMemberUserUuidAndEventId(memberUuid, eventId);
    }

    @Test
    void canScanEvent_noAssignments_deniesEveryEvent() {
        // Deny-by-default: a team member with NO assignments may scan nothing.
        // Flipped from the original "no rows = organizer-wide" behaviour at
        // operator request — the previous default surprised organizers by
        // showing team members every event before any explicit assignment.
        UUID memberUuid = UUID.randomUUID();
        UUID anyEventId = UUID.randomUUID();
        when(assignmentRepository.existsByTeamMemberUserUuidAndEventId(memberUuid, anyEventId))
                .thenReturn(false);

        assertThat(service.canScanEvent(memberUuid, anyEventId)).isFalse();
    }

    @Test
    void canScanEvent_withAssignments_onlyAssignedEventAllowed() {
        UUID memberUuid = UUID.randomUUID();
        UUID assignedEvent = UUID.randomUUID();
        UUID otherEvent = UUID.randomUUID();
        when(assignmentRepository.existsByTeamMemberUserUuidAndEventId(memberUuid, assignedEvent)).thenReturn(true);
        when(assignmentRepository.existsByTeamMemberUserUuidAndEventId(memberUuid, otherEvent)).thenReturn(false);

        assertThat(service.canScanEvent(memberUuid, assignedEvent)).isTrue();
        assertThat(service.canScanEvent(memberUuid, otherEvent)).isFalse();
    }

    @Test
    void assignedEventIdsFor_returnsEventIdsFromAssignmentRows() {
        // event-service uses this to scope /events/my for a team member to
        // ONLY the events they've been assigned to (deny-by-default). No
        // ownership check here — the caller is trusted via X-Internal-Token.
        UUID memberUuid = UUID.randomUUID();
        UUID eventA = UUID.randomUUID();
        UUID eventB = UUID.randomUUID();
        when(assignmentRepository.findByTeamMemberUserUuid(memberUuid))
                .thenReturn(List.of(
                        TeamMemberEventAssignment.builder().teamMemberUserUuid(memberUuid)
                                .eventId(eventA).assignedByOrganizerUuid(UUID.randomUUID()).build(),
                        TeamMemberEventAssignment.builder().teamMemberUserUuid(memberUuid)
                                .eventId(eventB).assignedByOrganizerUuid(UUID.randomUUID()).build()));

        assertThat(service.assignedEventIdsFor(memberUuid)).containsExactlyInAnyOrder(eventA, eventB);
    }

    @Test
    void assignedEventIdsFor_emptyWhenNoAssignments() {
        UUID memberUuid = UUID.randomUUID();
        when(assignmentRepository.findByTeamMemberUserUuid(memberUuid)).thenReturn(List.of());

        assertThat(service.assignedEventIdsFor(memberUuid)).isEmpty();
    }

    /** Shared minimal happy-path setup for the create flow — every delivery
     *  test below needs the same repo / encoder stubs. */
    private void primeCreateHappyPath() {
        authenticateAs(organizer(UUID.randomUUID()));
        when(userRepository.existsByEmail(any())).thenReturn(false);
        when(userRepository.existsByPhoneNumberAndHomeCountry(any(), any())).thenReturn(false);
        when(passwordEncoder.encode(any())).thenReturn("HASHED");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(99L);
            return u;
        });
    }

    @Test
    void create_publishesOnboardingEvent_offTheRequestThread() {
        // Credentials are delivered off-thread by the async CredentialDeliveryListener
        // (email -> SMS -> WhatsApp, best-effort); the service only publishes the
        // event, so a slow gateway can't stall the create response. The channel
        // fallback chain is the listener's concern (see CredentialDeliveryListenerTest).
        primeCreateHappyPath();

        service.createTeamMember(createDto());

        ArgumentCaptor<CredentialDeliveryRequested> ev =
                ArgumentCaptor.forClass(CredentialDeliveryRequested.class);
        verify(eventPublisher).publishEvent(ev.capture());
        assertThat(ev.getValue().reason()).isEqualTo(CredentialDeliveryRequested.Reason.ONBOARDING);
        assertThat(ev.getValue().email()).isEqualTo("tariro@harare-arena.co.zw");
        assertThat(ev.getValue().phoneNumber()).isEqualTo("+263773456789");
        verify(userRepository).save(any(User.class));
    }

    @Test
    void resetTemporaryPassword_rotatesPasswordAndFlagsForceChange() {
        UUID organizerUuid = UUID.randomUUID();
        UUID memberUuid = UUID.randomUUID();
        User member = teamMember(memberUuid, organizerUuid);
        member.setPassword("OLD_HASH");
        member.setMustChangePassword(false); // pretend they had set a permanent password
        authenticateAs(organizer(organizerUuid));
        when(userRepository.findByUserUuid(memberUuid)).thenReturn(Optional.of(member));
        when(passwordEncoder.encode(any())).thenReturn("NEW_HASH");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        UserResponseDTO result = service.resetTemporaryPassword(memberUuid);

        assertThat(result.getUserUuid()).isEqualTo(memberUuid);
        assertThat(member.getPassword()).isEqualTo("NEW_HASH");
        assertThat(member.isMustChangePassword()).isTrue();
        // Reset re-delivers off-thread via the same async event pipeline.
        ArgumentCaptor<CredentialDeliveryRequested> ev =
                ArgumentCaptor.forClass(CredentialDeliveryRequested.class);
        verify(eventPublisher).publishEvent(ev.capture());
        assertThat(ev.getValue().reason()).isEqualTo(CredentialDeliveryRequested.Reason.RESET);
        assertThat(ev.getValue().email()).isEqualTo("tariro@harare-arena.co.zw");
    }

    @Test
    void resetTemporaryPassword_returns404WhenMemberBelongsToDifferentOrganizer() {
        UUID callerUuid = UUID.randomUUID();
        UUID otherOrganizerUuid = UUID.randomUUID();
        UUID memberUuid = UUID.randomUUID();
        authenticateAs(organizer(callerUuid));
        when(userRepository.findByUserUuid(memberUuid))
                .thenReturn(Optional.of(teamMember(memberUuid, otherOrganizerUuid)));

        assertThatThrownBy(() -> service.resetTemporaryPassword(memberUuid))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
        // Crucially: no password write on a foreign member.
        verify(userRepository, never()).save(any(User.class));
        verify(passwordEncoder, never()).encode(any());
    }

    @Test
    void resetTemporaryPassword_doesNotBumpTokenVersionOrRevokeRefreshTokens() {
        // Reset is a "re-send credentials" operation, NOT a "kick this user
        // out" operation. Matches the SUPER_ADMIN reset behaviour. If you also
        // need to terminate sessions, the caller must disableTeamMember first
        // (which DOES revoke).
        UUID organizerUuid = UUID.randomUUID();
        UUID memberUuid = UUID.randomUUID();
        User member = teamMember(memberUuid, organizerUuid);
        long versionBefore = member.getTokenVersion();
        authenticateAs(organizer(organizerUuid));
        when(userRepository.findByUserUuid(memberUuid)).thenReturn(Optional.of(member));
        when(passwordEncoder.encode(any())).thenReturn("NEW_HASH");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        service.resetTemporaryPassword(memberUuid);

        assertThat(member.getTokenVersion()).isEqualTo(versionBefore);
        verify(refreshTokenRepository, never()).revokeAllForUser(anyLong(), any(Instant.class));
    }

    // -------------------------------------------------------------------------
    // SUPER_ADMIN visibility — pins that SUPER_ADMIN reads / acts on EVERY
    // team member across all organizers, and is NEVER 403'd by the
    // organizer-scoped checks. Organizer-scoped behaviour for the same calls
    // is covered by the cases above.
    // -------------------------------------------------------------------------

    private User superAdmin() {
        return User.builder()
                .id(7L)
                .userUuid(UUID.randomUUID())
                .email("admin@innbucks.co.zw")
                .firstName("Sys")
                .lastName("Admin")
                .phoneNumber("+263770000000")
                .roles(EnumSet.of(User.Role.SUPER_ADMIN))
                .active(true)
                .build();
    }

    private void authenticateAsSuperAdmin(User admin) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        admin.getEmail(), null,
                        List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                "ROLE_SUPER_ADMIN"))));
        // No userRepository.findByEmail stub here: the SUPER_ADMIN path is
        // role-claim-based (isCallerSuperAdmin reads the auth authorities) and
        // never resolves the admin in the DB. Strict Mockito rejects an
        // unused stub.
    }

    @Test
    void list_asSuperAdmin_returnsEveryTeamMember_acrossAllOrganizers() {
        authenticateAsSuperAdmin(superAdmin());
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        User memberA = teamMember(UUID.randomUUID(), orgA);
        User memberB = teamMember(UUID.randomUUID(), orgB);
        memberB.setId(100L);
        memberB.setEmail("staff-b@example.com");
        when(userRepository.findByAnyRole(List.of(User.Role.TEAM_MEMBER)))
                .thenReturn(List.of(memberA, memberB));

        List<UserResponseDTO> out = service.listMyTeam();

        assertThat(out).hasSize(2);
        assertThat(out).extracting(UserResponseDTO::getCreatedByOrganizerUuid)
                .containsExactlyInAnyOrder(orgA, orgB);
        verify(userRepository, never()).findByCreatedByOrganizerUuid(any(UUID.class));
    }

    @Test
    void list_asSuperAdmin_emptySystem_returnsEmptyList_neverForbidden() {
        // The whole point: admin must never see a 403 here. With no team
        // members anywhere on the platform we return [] cleanly.
        authenticateAsSuperAdmin(superAdmin());
        when(userRepository.findByAnyRole(List.of(User.Role.TEAM_MEMBER)))
                .thenReturn(List.of());

        assertThat(service.listMyTeam()).isEmpty();
    }

    @Test
    void get_asSuperAdmin_bypassesOrganizerOwnershipCheck() {
        authenticateAsSuperAdmin(superAdmin());
        UUID memberUuid = UUID.randomUUID();
        UUID otherOrganizer = UUID.randomUUID();
        User member = teamMember(memberUuid, otherOrganizer);
        when(userRepository.findByUserUuid(memberUuid)).thenReturn(Optional.of(member));

        UserResponseDTO out = service.getMyTeamMember(memberUuid);

        assertThat(out.getUserUuid()).isEqualTo(memberUuid);
        assertThat(out.getCreatedByOrganizerUuid()).isEqualTo(otherOrganizer);
    }

    @Test
    void get_asSuperAdmin_unknownUuid_returns404_notForbidden() {
        // Still 404 (not 403) for a uuid that doesn't resolve — admin just
        // sees "not found", same shape the organizer path returns.
        authenticateAsSuperAdmin(superAdmin());
        UUID missing = UUID.randomUUID();
        when(userRepository.findByUserUuid(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getMyTeamMember(missing))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void disable_asSuperAdmin_canDisableAnyOrganizersTeamMember() {
        authenticateAsSuperAdmin(superAdmin());
        UUID memberUuid = UUID.randomUUID();
        User member = teamMember(memberUuid, UUID.randomUUID());
        long versionBefore = member.getTokenVersion();
        when(userRepository.findByUserUuid(memberUuid)).thenReturn(Optional.of(member));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(refreshTokenRepository.revokeAllForUser(eq(member.getId()), any(Instant.class)))
                .thenReturn(2);

        UserResponseDTO out = service.disableTeamMember(memberUuid);

        assertThat(out.isActive()).isFalse();
        assertThat(member.getTokenVersion()).isEqualTo(versionBefore + 1);
        verify(refreshTokenRepository).revokeAllForUser(eq(member.getId()), any(Instant.class));
    }

    @Test
    void enable_asSuperAdmin_canReEnableAnyOrganizersTeamMember() {
        authenticateAsSuperAdmin(superAdmin());
        UUID memberUuid = UUID.randomUUID();
        User member = teamMember(memberUuid, UUID.randomUUID());
        member.setActive(false);
        when(userRepository.findByUserUuid(memberUuid)).thenReturn(Optional.of(member));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        UserResponseDTO out = service.enableTeamMember(memberUuid);

        assertThat(out.isActive()).isTrue();
    }

    @Test
    void assignEvent_asSuperAdmin_stampsOwningOrganizer_notAdmin() {
        // SUPER_ADMIN assigning records the team member's OWN organizer as
        // assignedBy — the admin's uuid would be misleading (admin has no
        // organizerUuid claim) and break per-organizer reporting on
        // assignments.
        authenticateAsSuperAdmin(superAdmin());
        UUID memberUuid = UUID.randomUUID();
        UUID owningOrganizer = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        User member = teamMember(memberUuid, owningOrganizer);
        when(userRepository.findByUserUuid(memberUuid)).thenReturn(Optional.of(member));
        when(assignmentRepository.existsByTeamMemberUserUuidAndEventId(memberUuid, eventId))
                .thenReturn(false);
        when(assignmentRepository.findByTeamMemberUserUuid(memberUuid)).thenReturn(List.of());
        ArgumentCaptor<TeamMemberEventAssignment> saved =
                ArgumentCaptor.forClass(TeamMemberEventAssignment.class);
        when(assignmentRepository.save(saved.capture()))
                .thenAnswer(inv -> inv.getArgument(0));

        service.assignEvent(memberUuid, eventId);

        assertThat(saved.getValue().getAssignedByOrganizerUuid()).isEqualTo(owningOrganizer);
        assertThat(saved.getValue().getTeamMemberUserUuid()).isEqualTo(memberUuid);
        assertThat(saved.getValue().getEventId()).isEqualTo(eventId);
    }
}
