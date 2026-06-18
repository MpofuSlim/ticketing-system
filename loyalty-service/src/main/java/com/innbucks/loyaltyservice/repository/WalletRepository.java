package com.innbucks.loyaltyservice.repository;

import com.innbucks.loyaltyservice.entity.Wallet;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WalletRepository extends JpaRepository<Wallet, UUID> {

    /** The customer's single wallet of a given type (one MAIN per phone). */
    Optional<Wallet> findFirstByPhoneNumberAndType(String phoneNumber, Wallet.Type type);

    /** Every wallet (MAIN + pockets) for a customer. */
    List<Wallet> findByPhoneNumber(String phoneNumber);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM Wallet w WHERE w.id = :id")
    Optional<Wallet> lockById(@Param("id") UUID id);
}
