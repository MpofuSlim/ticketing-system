package com.innbucks.userservice.repository;

import com.innbucks.userservice.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
