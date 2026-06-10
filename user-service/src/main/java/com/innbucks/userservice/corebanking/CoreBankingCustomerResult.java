package com.innbucks.userservice.corebanking;

import lombok.Builder;

/**
 * Result of a successful core-banking customer creation.
 *
 * @param profileRef       provider-agnostic reference persisted to
 *                         {@code customer_profiles.core_banking_profile_id}.
 *                         Oradian: the externalID; Veengu: the individual
 *                         profile id (our customer UUID, client-supplied).
 * @param oradianExternalId legacy column value — non-null only from the
 *                          Oradian adapter; Veengu leaves it null.
 * @param oradianClientId   legacy column value — non-null only from the
 *                          Oradian adapter.
 * @param status            provider's client/profile status (informational).
 * @param country           ISO 3166-1 alpha-2 echoed by the provider.
 */
@Builder
public record CoreBankingCustomerResult(
        String profileRef,
        String oradianExternalId,
        Long oradianClientId,
        String status,
        String country
) {}
