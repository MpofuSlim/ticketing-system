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
        String raw = service.issueNewFamily(alice);

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
        String first = service.issueNewFamily(alice);

        RefreshTokenService.Rotation r = service.rotate(first);

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
        String first = service.issueNewFamily(alice);
        RefreshTokenService.Rotation r = service.rotate(first);   // legit client rotates

        // Attacker (or buggy client) replays the original token.
        assertThrows(RefreshTokenService.ReuseDetectedException.class,
                () -> service.rotate(first));

        // Every row in the family must now be revoked, including the legit
        // successor that the real client is currently holding.
        for (RefreshToken row : store.values()) {
            assertNotNull(row.getRevokedAt(), "row should be revoked after family revocation: " + row.getId());
        }

        // The rotated-out successor also cannot be used after family revocation.
        assertThrows(RefreshTokenService.ReuseDetectedException.class,
                () -> service.rotate(r.refreshToken()));
    }

    @Test
    void rotate_rejectsAccessToken() {
        // An access token has no type=refresh claim; mint one and try to rotate.
        String access = jwtUtil.generateToken("alice@example.com", "CUSTOMER", 2, false);
        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.rotate(access));
        assertTrue(ex.getMessage().contains("Not a refresh token"));
    }

    @Test
    void rotate_rejectsUnknownRefreshToken() {
        // Token that's a valid JWT (type=refresh) but never stored in the table.
        String stranger = jwtUtil.generateRefreshToken("eve@example.com");
        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.rotate(stranger));
        assertTrue(ex.getMessage().contains("not recognised"));
    }
}
