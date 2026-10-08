package com.innbucks.userservice.repository;

import com.innbucks.userservice.entity.Device;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DeviceRepository extends JpaRepository<Device, Long> {
    List<Device> findByUserId(Long userId);
    Optional<Device> findByUserIdAndDeviceId(Long userId, String deviceId);

    /**
     * Every device row of an account that is being DELETED (a rejected
     * registration). {@code devices.user_id} has no cascade (V1). A registration
     * pending approval cannot sign in, so there are normally none.
     */
    @Modifying
    @Query("delete from Device d where d.user.id = :userId")
    int deleteAllForUser(@Param("userId") Long userId);
}
