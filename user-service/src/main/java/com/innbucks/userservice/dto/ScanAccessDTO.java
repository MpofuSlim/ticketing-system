package com.innbucks.userservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * S2S result of "may this team member scan tickets for this event?". Encodes
 * the assignment rule so booking-service doesn't need to know it — it just
 * reads {@code allowed}.
 *
 * <p><b>The rule is deny-by-default:</b> {@code allowed} is true only when an
 * assignment row exists for exactly this (member, event) pair. A member with NO
 * assignment rows can scan NOTHING — not "everything their organizer owns".
 * V21 introduced the opposite (no rows = organizer-wide) and {@code faf4b86a}
 * implemented it, but {@code 19ec675f} "deny-by-default team-member event
 * access" removed that early-return deliberately. Comments describing the old
 * behaviour outlived the change; this one is checked against
 * {@code TeamMemberService.canScanEvent}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScanAccessDTO {
    private boolean allowed;
}
