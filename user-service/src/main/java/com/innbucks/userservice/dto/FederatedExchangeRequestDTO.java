package com.innbucks.userservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Body of {@code POST /auth/exchange}. Deliberately carries NOTHING but the
 * assertion: the phone comes from its signed {@code sub}, never from a second
 * field a caller could pair with someone else's valid assertion.
 */
@Data
@Schema(name = "FederatedExchangeRequest",
        description = "A short-lived assertion signed by the InnBucks middleware after the customer "
                + "logged in there. The phone number is read from its signed `sub` claim only.")
public class FederatedExchangeRequestDTO {

    @Schema(description = "Compact JWS (RS256/ES256), `iss`/`aud` as provisioned, `sub` = the customer's "
            + "phone, `jti`, `iat`, `exp` ≤ 5 minutes after `iat`.",
            example = "eyJhbGciOiJSUzI1NiJ9.eyJpc3MiOiJpbm5idWNrcy1taWRkbGV3YXJlIiwiYXVkIjoiaW5uYnVja3MtZm91bmRyeSIsInN1YiI6IisyNjM3NzEyMzQ1NjciLCJqdGkiOiI3YzUxYzM4ZS0uLi4iLCJpYXQiOjE3NTc0OTQ4MDAsImV4cCI6MTc1NzQ5NTEwMH0.sig")
    @NotBlank(message = "assertion is required")
    @Size(max = 8192, message = "assertion is too long")
    private String assertion;
}
