package com.innbucks.userservice.repository;

import com.innbucks.userservice.entity.MfaBackupCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface MfaBackupCodeRepository extends JpaRepository<MfaBackupCode, Long> {

    /** All still-usable codes for a user (need them all because bcrypt requires comparing one-by-one). */
    @Query("SELECT c FROM MfaBackupCode c WHERE c.userId = :userId AND c.usedAt IS NULL")
    List<MfaBackupCode> findUnusedByUserId(@Param("userId") Long userId);

    /**
     * Atomically mark a code consumed: hits 0 rows if another concurrent verify
     * already consumed it, so a backup code wins exactly one race. Caller
     * checks the row count to decide whether to accept the login.
     */
    @Modifying
    @Query("UPDATE MfaBackupCode c SET c.usedAt = :now WHERE c.id = :id AND c.usedAt IS NULL")
    int markUsed(@Param("id") Long id, @Param("now") LocalDateTime now);

    /** Wipe every code for a user — used when minting a fresh batch or on admin reset. */
    @Modifying
    @Query("DELETE FROM MfaBackupCode c WHERE c.userId = :userId")
    int deleteAllForUser(@Param("userId") Long userId);
}
