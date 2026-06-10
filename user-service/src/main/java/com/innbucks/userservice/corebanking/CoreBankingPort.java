package com.innbucks.userservice.corebanking;

import com.innbucks.userservice.client.DepositAccount;

import java.util.List;

/**
 * Port to the per-cell core-banking system. Each country cell is wired to
 * exactly ONE provider at deploy time via {@code innbucks.core-banking.provider}
 * ({@code INNBUCKS_CORE_BANKING} env var): the Kenya cell runs Oradian
 * (Instafin via OradianMiddleware), the Zimbabwe cell will run Veengu
 * (InnBucks core, public Frontend API). Selection is a deployment-level
 * concern — never per-request — because cells are single-country and
 * customers are home-anchored to their cell.
 *
 * <p>The surface is deliberately minimal: exactly what the registration/auth
 * domain consumes today. Payments-side operations (transfers, withdrawals,
 * debits) stay on their own clients in payment-service and get the same SPI
 * treatment in a later phase.
 *
 * <p>Contract notes:
 * <ul>
 *   <li>{@link #createCustomer} is BLOCKING and never returns null — a
 *       provider failure or empty response throws the adapter's exception
 *       (e.g. {@code OradianClientException}), which rolls back the caller's
 *       transaction and surfaces as 502. The {@code idempotencyKey} must be
 *       stable per customer so retries replay rather than double-create.</li>
 *   <li>{@link #listDeposits} returns an empty list for "no accounts", and
 *       throws on provider failure.</li>
 * </ul>
 */
public interface CoreBankingPort {

    /**
     * Provider tag stamped onto {@code customer_profiles.core_banking_provider}
     * (e.g. {@code "ORADIAN"}, {@code "VEENGU"}). Uppercase, stable — it is
     * persisted and joined on by reconciliation tooling.
     */
    String provider();

    CoreBankingCustomerResult createCustomer(CoreBankingCreateCustomerCommand command, String idempotencyKey);

    List<DepositAccount> listDeposits(String msisdn);
}
