package com.innbucks.userservice.repository;

import com.innbucks.userservice.entity.PosApiKey;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface PosApiKeyRepository extends JpaRepository<PosApiKey, UUID> {

    Optional<PosApiKey> findByKeyHash(String keyHash);

    Page<PosApiKey> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<PosApiKey> findByMerchantIdOrderByCreatedAtDesc(UUID merchantId, Pageable pageable);

    /**
     * Revoke a live key and move its session epoch on, in one statement. Returns
     * the number of rows changed: 0 when the key does not exist or was already
     * revoked, so two concurrent revokes bump once.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE pos_api_keys
               SET revoked_at = :now, revoked_by = :by, token_version = token_version + 1
             WHERE id = :id AND revoked_at IS NULL
            """, nativeQuery = true)
    int revoke(@Param("id") UUID id, @Param("now") LocalDateTime now, @Param("by") String by);

    /**
     * Stamp the last exchange, at most once per {@code since} window per key, so
     * a busy integration costs one write every few minutes rather than per token.
     */
    @Modifying
    @Query(value = """
            UPDATE pos_api_keys SET last_used_at = :now
             WHERE id = :id AND (last_used_at IS NULL OR last_used_at < :since)
            """, nativeQuery = true)
    int touch(@Param("id") UUID id, @Param("now") LocalDateTime now, @Param("since") LocalDateTime since);
}
