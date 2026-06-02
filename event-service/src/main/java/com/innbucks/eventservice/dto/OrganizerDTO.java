package com.innbucks.eventservice.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

/**
 * Business details of the event's owning organizer (tenant), resolved from
 * user-service's tenant profile via {@code OrganizerGateway}. Attached to every
 * event response; null when the organizer has no business profile or
 * user-service could not be reached (the listing still returns).
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

    @Schema(example = "hello@showtime.co.zw", description = "Business contact email.")
    private String email;

    @Schema(example = "5 Leopold Takawira St, Bulawayo", description = "Business address.")
    private String address;
}
