package com.innbucks.userservice.repository;

import com.innbucks.userservice.entity.OtpRetryAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OtpRetryAttemptRepository extends JpaRepository<OtpRetryAttempt, Long> {

    Optional<OtpRetryAttempt> findByPhoneNumber(String phoneNumber);
}
