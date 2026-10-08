package com.innbucks.userservice.repository;

import com.innbucks.userservice.entity.CustomerProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CustomerProfileRepository extends JpaRepository<CustomerProfile, Long> {
    Optional<CustomerProfile> findByUserId(Long userId);

    /**
     * Whether the account also holds a super-app customer profile. Rejecting a
     * registration refuses such an account: removing it would delete a
     * customer, and {@code customer_profiles.user_id} has no cascade (V1).
     */
    boolean existsByUserId(Long userId);
}
