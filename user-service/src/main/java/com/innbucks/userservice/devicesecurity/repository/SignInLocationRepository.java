package com.innbucks.userservice.devicesecurity.repository;

import com.innbucks.userservice.devicesecurity.entity.SignInLocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface SignInLocationRepository extends JpaRepository<SignInLocation, Long> {

    /** Bounded on purpose: the places model needs a customer's recent habits, not their whole history. */
    List<SignInLocation> findTop200ByMsisdnOrderByOccurredAtDesc(String msisdn);

    @Modifying
    @Query("delete from SignInLocation l where l.occurredAt < :cutoff")
    int deleteOccurredBefore(@Param("cutoff") LocalDateTime cutoff);
}
