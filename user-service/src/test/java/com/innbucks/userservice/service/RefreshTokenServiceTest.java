package com.innbucks.userservice.service;

import com.innbucks.userservice.entity.RefreshToken;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.RefreshTokenRepository;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.security.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class RefreshTokenServiceTest {

    private JwtUtil jwtUtil;
    private RefreshTokenRepository repo;
    private UserRepository userRepo;
    private RefreshTokenService service;

    // Simulates the unique-token-hash table behaviour with an in-memory map.
    private Map<String, RefreshToken> store;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secret", "test-test-test-test-test-test-test-test");
        ReflectionTestUtils.setField(jwtUtil, "expiration", 3_600_000L);
        ReflectionTestUtils.setField(jwtUtil, "refreshExpiration", 86_400_000L);

        repo = mock(RefreshTokenRepository.class);
        userRepo = mock(UserRepository.class);
        store = new HashMap<>();
        // Capture saves into the in-memory store keyed by token_hash.
        when(repo.save(any(RefreshToken.class))).thenAnswer(inv -> {
            RefreshToken row = inv.getArgument(0);
            store.put(row.getTokenHash(), row);
            return row;
        });
        when(repo.findByTokenHash(any())).thenAnswer(inv ->
                Optional.ofNullable(store.get((String) inv.getArgument(0))));
        when(repo.revokeFamily(any(UUID.class), any(Instant.class))).thenAnswer(inv -> {
            UUID familyId = inv.getArgument(0);
            Instant now = inv.getArgument(1);
            int n = 0;
            for (RefreshToken row : store.values()) {
                if (familyId.equals(row.getFamilyId()) && row.getRevokedAt() == null) {
                    row.setRevokedAt(now);
                    n++;
                }
            }
            return n;
        });

        service = new RefreshTokenService(repo, userRepo, jwtUtil);
    }

    private User aliceWithId(long id) {
        User u = User.builder().id(id).email("alice@example.com").password("x")
                .active(true).build();
        when(userRepo.findById(id)).thenReturn(Optional.of(u));
        return u;
    }

    @Test
    void issueNewFamily_persistsRowWithFreshFamilyId() {
        User alice = aliceWithId(1L);
        String raw = service.issueNewFamily(alice, null);

        assertNotNull(raw);
        assertEquals(1, store.size());
        RefreshToken row = store.values().iterator().next();
        assertNotNull(row.getFamilyId());
        assertNull(row.getParentId());
        assertNull(row.getRevokedAt());
        assertEquals(alice.getId(), row.getUserId());
    }

    @Test
    void rotate_consumesOldAndChainsNew() {
        User alice = aliceWithId(1L);
        String first = service.issueNewFamily(alice, null);

        RefreshTokenService.Rotation r = service.rotate(first, null);

        assertNotNull(r.refreshToken());
        assertNotEquals(first, r.refreshToken());

        List<RefreshToken> rows = new ArrayList<>(store.values());
        RefreshToken consumed = rows.stream().filter(x -> x.getParentId() == null).findFirst().orElseThrow();
        RefreshToken successor = rows.stream().filter(x -> x.getParentId() != null).findFirst().orElseThrow();

        assertNotNull(consumed.getRevokedAt());
        assertEquals(successor.getId(), consumed.getReplacedById());
        assertEquals(consumed.getFamilyId(), successor.getFamilyId());
        assertEquals(consumed.getId(), successor.getParentId());
        assertNull(successor.getRevokedAt());
    }

    @Test
    void rotate_secondTimeOnSameToken_throwsReuseAndRevokesFamily() {
        User alice = aliceWithId(1L);
        String first = service.issueNewFamily(alice, null);
        RefreshTokenService.Rotation r = service.rotate(first, null);   // legit client rotates

        // Attacker (or buggy client) replays the original token.
        assertThrows(RefreshTokenService.ReuseDetectedException.class,
                () -> service.rotate(first, null));

        // Every row in the family must now be revoked, including the legit
        // successor that the real client is currently holding.
        for (RefreshToken row : store.values()) {
            assertNotNull(row.getRevokedAt(), "row should be revoked after family revocation: " + row.getId());
        }

        // The rotated-out successor also cannot be used after family revocation.
        assertThrows(RefreshTokenService.ReuseDetectedException.class,
                () -> service.rotate(r.refreshToken(), null));
    }

    @Test
    void rotate_rejectsAccessToken() {
        // An access token has no type=refresh claim; mint one and try to rotate.
        String access = jwtUtil.generateToken("alice@example.com", "CUSTOMER", 2, false);
        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.rotate(access, null));
        assertTrue(ex.getMessage().contains("Not a refresh token"));
    }

    @Test
    void rotate_rejectsUnknownRefreshToken() {
        // Token that's a valid JWT (type=refresh) but never stored in the table.
        String stranger = jwtUtil.generateRefreshToken("eve@example.com");
        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.rotate(stranger, null));
        assertTrue(ex.getMessage().contains("not recognised"));
    }

    @Test
    void issueNewFamily_storesSha256HashOfDeviceId_notTheRawValue() {
        // The DB column is 64 chars (hex SHA-256). The raw device id must
        // never reach storage — a database leak should expose hashes, not
        // identifiers a leaker could correlate back to a phone.
        User alice = aliceWithId(1L);
        service.issueNewFamily(alice, "device-abc-123");

        RefreshToken row = store.values().iterator().next();
        assertNotNull(row.getDeviceIdHash());
        assertEquals(64, row.getDeviceIdHash().length(), "device_id_hash must be hex SHA-256 (64 chars)");
        assertNotEquals("device-abc-123", row.getDeviceIdHash());
        assertEquals(RefreshTokenService.sha256("device-abc-123"), row.getDeviceIdHash());
    }

    @Test
    void issueNewFamily_storesNullHash_whenDeviceIdMissing() {
        // Backward-compat for legacy clients that don't send X-Device-Id yet.
        // Null lets the later rotate path skip device enforcement on this
        // family — old FE keeps working through the refresh-token TTL.
        User alice = aliceWithId(1L);
        service.issueNewFamily(alice, null);
        service.issueNewFamily(alice, "");
        service.issueNewFamily(alice, "  ");

        for (RefreshToken row : store.values()) {
            assertNull(row.getDeviceIdHash(),
                    "null/blank device id must store NULL — legacy sessions skip enforcement");
        }
    }

    @Test
    void rotate_succeeds_whenDeviceIdMatchesIssuingDevice() {
        User alice = aliceWithId(1L);
        String first = service.issueNewFamily(alice, "iphone-15-uuid");

        RefreshTokenService.Rotation r = service.rotate(first, "iphone-15-uuid");

        assertNotNull(r.refreshToken());
        // Successor inherits the same device binding so subsequent rotates
        // stay locked to the original device throughout the family's life.
        RefreshToken successor = store.values().stream()
                .filter(row -> row.getParentId() != null)
                .findFirst().orElseThrow();
        assertEquals(RefreshTokenService.sha256("iphone-15-uuid"), successor.getDeviceIdHash());
    }

    @Test
    void rotate_treatsMismatchedDeviceIdAsTheft_revokesFamily() {
        // Attacker scraped the refresh token off device A's storage and is
        // replaying it from device B. The hashes don't match — same
        // family-revoke side effect as a replay attack.
        User alice = aliceWithId(1L);
        String first = service.issueNewFamily(alice, "device-A");

        assertThrows(RefreshTokenService.ReuseDetectedException.class,
                () -> service.rotate(first, "device-B"));

        for (RefreshToken row : store.values()) {
            assertNotNull(row.getRevokedAt(),
                    "device mismatch must revoke the entire family, forcing re-login");
        }
    }

    @Test
    void rotate_treatsMissingDeviceIdHeaderAsTheft_whenRowIsBound() {
        // Row was minted with a device hash, but the rotate request didn't
        // send X-Device-Id at all. Treat the same as a mismatch — an
        // attacker stripping headers shouldn't bypass the check.
        User alice = aliceWithId(1L);
        String first = service.issueNewFamily(alice, "device-A");

        assertThrows(RefreshTokenService.ReuseDetectedException.class,
                () -> service.rotate(first, null));

        for (RefreshToken row : store.values()) {
            assertNotNull(row.getRevokedAt());
        }
    }

    @Test
    void rotate_skipsEnforcement_whenRowHasNoStoredHash_legacySession() {
        // Refresh token row was minted before the device-binding rollout
        // (stored hash is NULL). The rotate path must accept it without
        // checking the request's X-Device-Id — old FE keeps working until
        // the refresh-token TTL forces a fresh login.
        User alice = aliceWithId(1L);
        String first = service.issueNewFamily(alice, null);

        // Even with a wildly different device id, the rotate succeeds
        // because the row never carried a binding to compare against.
        RefreshTokenService.Rotation r = service.rotate(first, "any-device-id");
        assertNotNull(r.refreshToken());
    }
}
