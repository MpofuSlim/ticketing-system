package com.innbucks.userservice.repository;

import com.innbucks.userservice.entity.Device;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DeviceRepository extends JpaRepository<Device, Long> {
    List<Device> findByUserId(Long userId);
    Optional<Device> findByUserIdAndDeviceId(Long userId, String deviceId);
}
