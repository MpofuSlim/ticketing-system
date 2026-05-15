package com.innbucks.userservice.client;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * One deposit account row as returned by Oradian middleware's
 * GET /internal/customers/{msisdn}/deposits endpoint. Field types and names
 * mirror the middleware DTO verbatim so Jackson can deserialise without
 * remapping. Wire-format quirks (balance / boolean flags arriving as strings,
 * empty strings instead of nulls) are preserved — the SuperApp can parse them.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DepositAccount {
    private String internalID;
    private String ID;
    private String externalAccountNumber;
    private String clientInternalID;
    private String productID;
    private String productName;
    private String balance;
    private String currencyCode;
    private String status;
    private String isMainAccount;
    private String isMessagingFeeAccount;
    private String isJointAccount;
    private String subscribed;
    private LocalDate appliedDate;
    private LocalDate startDate;
    private LocalDate endDate;
    private LocalDate closeDate;
}
