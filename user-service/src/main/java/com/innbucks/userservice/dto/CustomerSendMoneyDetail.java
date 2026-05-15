package com.innbucks.userservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Slim projection of DepositAccount returned by
 * GET /auth/customer/send-money/details/{phoneNumber}. Excludes balance,
 * subscribed, and lifecycle dates so a sender looking up a recipient sees
 * only what they need to pick the right account — not the recipient's
 * private balance or account history.
 *
 * Same upstream Oradian call as /auth/customer/deposits; CustomerService
 * does the field-level redaction.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerSendMoneyDetail {
    private String internalID;
    private String ID;
    private String externalAccountNumber;
    private String clientInternalID;
    private String productID;
    private String productName;
    private String currencyCode;
    private String status;
    private String isMainAccount;
    private String isMessagingFeeAccount;
    private String isJointAccount;
}
