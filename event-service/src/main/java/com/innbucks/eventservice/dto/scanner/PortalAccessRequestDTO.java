package com.innbucks.eventservice.dto.scanner;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Where to send a sales agent's Foundry portal invite (V21). Checked by
 * user-service, which owns the invite: a refusal comes back with its own
 * {@code errorCode}.
 */
@Schema(name = "PortalAccessRequest")
public record PortalAccessRequestDTO(
        @Schema(example = "tendai.moyo@gmail.com", requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 254,
                description = "The agent's own address. InnBucks addresses are refused (email_domain_reserved).")
        String email
) { }
