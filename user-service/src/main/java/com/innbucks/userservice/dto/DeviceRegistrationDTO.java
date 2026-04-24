package com.innbucks.userservice.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class DeviceRegistrationDTO {

    @NotBlank(message = "Device ID is required")
    private String deviceId;

    private String deviceName;

    private String platform;

    private String pushToken;
}
