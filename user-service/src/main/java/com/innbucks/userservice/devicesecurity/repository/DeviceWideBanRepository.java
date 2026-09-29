package com.innbucks.userservice.devicesecurity.repository;

import com.innbucks.userservice.devicesecurity.entity.DeviceWideBan;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceWideBanRepository extends JpaRepository<DeviceWideBan, String> {
}
