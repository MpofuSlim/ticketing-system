package com.innbucks.userservice.service;

import com.innbucks.userservice.entity.RefreshToken;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.exception.OrganizationException;
import com.innbucks.userservice.repository.RefreshTokenRepository;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.security.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * The organization a session acts for rides the refresh row (V39), the same
 * way phone_proof does — because {@code /auth/refresh} re-derives the claims
 * from the live user, a choice kept anywhere else would evaporate on the first
 * rotation.
 *
 * <p>The load-bearing property is the last two cases: a REFUSED switch throws
 * before anything is consumed, so the client still holds a working session.
 */
class RefreshTokenOrganizationTest {

    private RefreshTokenRepository repo;
    private UserRepository userRepo;
    private OrganizationService organizations;
    private RefreshTokenService service;
    private final Map<String, RefreshToken> store = new HashMap<>();

    private final UUID orgA = UUID.randomUUID();
    private final UUID orgB = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        JwtUtil jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secret", "test-test-test-test-test-test-test-test");
        ReflectionTestUtils.setField(jwtUtil, "expiration", 3_600_000L);
        ReflectionTestUtils.setField(jwtUtil, "refreshExpiration", 86_400_000L);

        repo = mock(RefreshTokenRepository.class);
        userRepo = mock(UserRepository.class);
        organizations = mock(OrganizationService.class);
        when(repo.save(any(RefreshToken.class))).thenAnswer(inv -> {
            RefreshToken row = inv.getArgument(0);
            store.put(row.getTokenHash(), row);
            return row;
        });
        when(repo.findByTokenHash(any())).thenAnswer(inv -> Optional.ofNullable(store.get((String) inv.getArgument(0))));
        when(repo.revokeFamily(any(UUID.class), any(Instant.class))).thenReturn(0);

        service = new RefreshTokenService(repo, userRepo, jwtUtil);
        ReflectionTestUtils.setField(service, "organizationService", organizations);
    }

    private User rudo() {
        User u = User.builder().id(1L).email("rudo@example.com").password("x").active(true).build();
        when(userRepo.findById(1L)).thenReturn(Optional.of(u));
        return u;
    }

    @Test
    @DisplayName("a new session records the person's only organization")
    void newFamilyRecordsDefaultOrganization() {
        User u = rudo();
        when(organizations.defaultOrganizationFor(u)).thenReturn(orgA);

        service.issueNewFamily(u, null);

        assertThat(store.values()).singleElement().extracting(RefreshToken::getOrganizationId).isEqualTo(orgA);
    }

    @Test
    @DisplayName("a phone-proof session never records an organization, and never asks")
    void phoneProofFamilyRecordsNoOrganization() {
        User u = rudo();

        service.issueNewFamily(u, null, true);

        assertThat(store.values()).singleElement().extracting(RefreshToken::getOrganizationId).isNull();
        verifyNoInteractions(organizations);
    }

    @Test
    @DisplayName("every rotation re-checks the organization against live memberships")
    void rotationRevalidates() {
        User u = rudo();
        when(organizations.defaultOrganizationFor(u)).thenReturn(orgA);
        String first = service.issueNewFamily(u, null);
        // Removed from A since login; now only in B.
        when(organizations.revalidate(u, orgA)).thenReturn(orgB);

        RefreshTokenService.Rotation r = service.rotate(first, null);

        assertThat(r.organizationId()).isEqualTo(orgB);
        verify(organizations).revalidate(u, orgA);
    }

    @Test
    @DisplayName("a switch moves the session into the chosen organization")
    void switchStampsTheChosenOrganization() {
        User u = rudo();
        when(organizations.defaultOrganizationFor(u)).thenReturn(null);
        String first = service.issueNewFamily(u, null);
        when(organizations.requireSelectable(u, orgB)).thenReturn(orgB);

        RefreshTokenService.Rotation r = service.rotateInto(first, null, orgB);

        assertThat(r.organizationId()).isEqualTo(orgB);
        assertThat(store.values()).filteredOn(row -> row.getParentId() != null)
                .singleElement().extracting(RefreshToken::getOrganizationId).isEqualTo(orgB);
    }

    @Test
    @DisplayName("a refused switch consumes nothing — the presented token still rotates")
    void refusedSwitchLeavesSessionIntact() {
        User u = rudo();
        when(organizations.defaultOrganizationFor(u)).thenReturn(orgA);
        String first = service.issueNewFamily(u, null);
        when(organizations.requireSelectable(eq(u), eq(orgB))).thenThrow(OrganizationException.notFound());

        assertThatThrownBy(() -> service.rotateInto(first, null, orgB))
                .isInstanceOf(OrganizationException.class);

        assertThat(store).hasSize(1);
        assertThat(store.values().iterator().next().getRevokedAt()).isNull();
        when(organizations.revalidate(u, orgA)).thenReturn(orgA);
        assertThat(service.rotate(first, null).organizationId()).isEqualTo(orgA);
    }

    @Test
    @DisplayName("a phone-proof session can't be moved into a business, and keeps working")
    void phoneProofSwitchRefusedWithoutConsuming() {
        User u = rudo();
        String first = service.issueNewFamily(u, null, true);

        assertThatThrownBy(() -> service.rotateInto(first, null, orgA))
                .isInstanceOf(OrganizationException.class)
                .extracting(t -> ((OrganizationException) t).getErrorCode())
                .isEqualTo("organization_context_not_allowed");

        assertThat(store).hasSize(1);
        assertThat(store.values().iterator().next().getRevokedAt()).isNull();
        verify(organizations, never()).requireSelectable(any(), any());
    }
}
