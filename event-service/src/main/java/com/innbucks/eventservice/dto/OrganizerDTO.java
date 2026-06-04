package com.innbucks.eventservice.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

/**
 * Business details of the event's owning organizer (tenant), resolved from
 * user-service's tenant profile via {@code OrganizerGateway}. Attached to every
 * event response; null when the organizer has no business profile or
 * user-service could not be reached (the listing still returns).
 *
 * <p>Field names mirror the {@code /auth/register} payload
 * ({@code businessName}, {@code businessAddress}, {@code businessEmail}) so
 * the FE sees the same vocabulary on the way in and on the way out.
 * {@code bpoNumber} is intentionally NOT exposed here — it's a business
 * registration identifier and stays admin-only (see {@code BusinessDetails}
 * on {@code /admin/users/merchants}).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "Organizer", description = "Owning organizer's business details.")
public class OrganizerDTO {

    @Schema(example = "Showtime Events", description = "Registered business name of the organizer.")
    private String businessName;

    @Schema(example = "5 Leopold Takawira St, Bulawayo", description = "Business address.")
    private String businessAddress;

    @Schema(example = "hello@showtime.co.zw", description = "Business contact email.")
    private String businessEmail;
}
