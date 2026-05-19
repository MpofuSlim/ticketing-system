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
    // Recipient identity — denormalised onto every deposit row so the sender's
    // UI doesn't need a second round-trip to confirm "am I really sending to
    // the right person?" before picking an account. Same first/middle/last
    // across every row in a given response (one customer, many accounts).
    private String firstName;
    private String middleName;
    private String lastName;

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
