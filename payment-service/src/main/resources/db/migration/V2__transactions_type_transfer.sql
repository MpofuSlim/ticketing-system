-- Rename TransactionType.DEPOSIT -> TRANSFER on the transactions ledger.
-- The endpoint URL changed from POST /payments/deposit to POST /payments/
-- transfer because the operation is a transfer between two Oradian deposit
-- accounts (Oradian's wire name is SubmitDepositAccountTransfer), not a
-- deposit in the conventional banking sense. The enum value follows the URL.
--
-- Done as a separate migration rather than editing V1 because Flyway
-- checksums committed migrations — editing V1 would break Flyway's
-- repeat-of-truth invariant in any environment where V1 has already run.

UPDATE transactions
   SET transaction_type = 'TRANSFER'
 WHERE transaction_type = 'DEPOSIT';

ALTER TABLE transactions DROP CONSTRAINT chk_transactions_type;

ALTER TABLE transactions
    ADD CONSTRAINT chk_transactions_type
    CHECK (transaction_type IN ('TRANSFER', 'WITHDRAWAL'));
