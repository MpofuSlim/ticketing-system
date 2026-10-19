package com.innbucks.userservice.devicesecurity.repository;

import java.time.LocalDateTime;

/** Who a login ticket is for: the number, its device row (null for a LOOKUP), the handset, and when it was minted. */
public record TicketRef(String msisdn, Long customerDeviceId, String installIdHash, LocalDateTime issuedAt) {
}
