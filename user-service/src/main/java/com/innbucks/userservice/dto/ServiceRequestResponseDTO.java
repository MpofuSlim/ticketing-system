package com.innbucks.userservice.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.innbucks.userservice.entity.ServiceRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "ServiceRequestResponse",
        description = "A user's request to be granted access to an additional default service.")
public class ServiceRequestResponseDTO {

    @Schema(example = "12")
    private Long id;

    @Schema(example = "42", description = "id of the user who submitted the request")
    private Long userId;

    @Schema(example = "alice@innbucks.co.zw", description = "email of the user who submitted the request")
    private String userEmail;

    @Schema(example = "Alice Moyo", description = "full name of the user who submitted the request")
    private String userFullName;

    @Schema(example = "loyalty", description = "Bundle being requested")
    private String service;

    @Schema(example = "We are launching a rewards programme.")
    private String reason;

    @Schema(example = "PENDING", allowableValues = {"PENDING", "APPROVED"})
    private String status;

    @Schema(example = "2026-05-07T10:30:00")
    private LocalDateTime createdAt;

    @Schema(example = "2026-05-08T09:15:00", nullable = true)
    private LocalDateTime reviewedAt;

    @Schema(example = "1", nullable = true, description = "id of the SUPER_ADMIN who approved")
    private Long reviewedBy;

    public static ServiceRequestResponseDTO from(ServiceRequest req,
                                                 String userEmail,
                                                 String userFullName) {
        return ServiceRequestResponseDTO.builder()
                .id(req.getId())
                .userId(req.getUserId())
                .userEmail(userEmail)
                .userFullName(userFullName)
                .service(req.getService())
                .reason(req.getReason())
                .status(req.getStatus().name())
                .createdAt(req.getCreatedAt())
                .reviewedAt(req.getReviewedAt())
                .reviewedBy(req.getReviewedBy())
                .build();
    }
}
