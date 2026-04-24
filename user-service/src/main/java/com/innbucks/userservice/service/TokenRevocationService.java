package com.innbucks.userservice.service;

import com.innbucks.userservice.entity.RevokedToken;
import com.innbucks.userservice.repository.RevokedTokenRepository;
import com.innbucks.userservice.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

@Service
@RequiredArgsConstructor
@Slf4j
public class TokenRevocationService {

    private final RevokedTokenRepository revokedTokenRepository;
    private final JwtUtil jwtUtil;

    @Transactional
    public void revoke(String token) {
        if (!jwtUtil.isTokenValid(token)) {
            throw new RuntimeException("Cannot revoke an invalid or expired token");
        }
        String hash = hash(token);
        if (revokedTokenRepository.existsByTokenHash(hash)) {
            log.debug("Token already revoked, skipping");
            return;
        }
        RevokedToken entry = RevokedToken.builder()
                .tokenHash(hash)
                .subject(jwtUtil.extractEmail(token))
                .expiresAt(jwtUtil.extractExpiration(token).toInstant())
                .revokedAt(Instant.now())
                .build();
        revokedTokenRepository.save(entry);
        log.info("Token revoked subject={}", entry.getSubject());
    }

    public boolean isRevoked(String token) {
        return revokedTokenRepository.existsByTokenHash(hash(token));
    }

    @Scheduled(fixedDelayString = "PT1H")
    @Transactional
    public void purgeExpired() {
        int removed = revokedTokenRepository.deleteExpired(Instant.now());
        if (removed > 0) {
            log.info("Purged {} expired revoked-token entries", removed);
        }
    }

    static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] out = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(out);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
