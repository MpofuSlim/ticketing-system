package innbucks.paymentservice.entity;

/**
 * Discriminator on the {@code transactions} ledger. Lets the same table
 * carry deposit-account transfers and withdrawals so the customer-history
 * query stays a single index scan.
 *
 * <p>{@code TRANSFER} corresponds to {@code POST /payments/transfers}
 * (Oradian's {@code SubmitDepositAccountTransfer} — money moves between
 * two Oradian deposit accounts). {@code WITHDRAWAL} corresponds to
 * {@code POST /payments/withdraw} (Oradian's
 * {@code EnterWithdrawalOnDepositAccount} — money leaves one account by
 * cash / mobile-money / etc.).
 */
public enum TransactionType {
    TRANSFER,
    WITHDRAWAL
}
