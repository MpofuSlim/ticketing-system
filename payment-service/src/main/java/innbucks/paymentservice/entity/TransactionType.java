package innbucks.paymentservice.entity;

/**
 * Discriminator on the {@code transactions} ledger. Lets the same table
 * carry deposits and withdrawals so the customer-history query stays a
 * single index scan.
 */
public enum TransactionType {
    DEPOSIT,
    WITHDRAWAL
}
