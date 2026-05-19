package com.innbucks.userservice.repository;

import com.innbucks.userservice.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
    Optional<User> findByPhoneNumber(String phoneNumber);
    boolean existsByEmail(String email);
    boolean existsByPhoneNumber(String phoneNumber);
    List<User> findByActive(boolean active);
    List<User> findByLoyaltyShopId(UUID loyaltyShopId);
    List<User> findByLoyaltyMerchantId(UUID loyaltyMerchantId);

    /**
     * Project-only lookup for the token_version column. JwtFilter calls this
     * on every authenticated request to validate the JWT's session epoch
     * against the current DB value — fetching the column directly avoids
     * loading the whole {@link User} entity (with its eager
     * {@code roles} and {@code defaultServices} collections) just to read
     * one number. Returns empty when the subject doesn't resolve to a user,
     * which JwtFilter treats the same as a stale token.
     */
    @Query("SELECT u.tokenVersion FROM User u WHERE u.email = :subject OR u.phoneNumber = :subject")
    Optional<Long> findTokenVersionBySubject(@Param("subject") String subject);
}
