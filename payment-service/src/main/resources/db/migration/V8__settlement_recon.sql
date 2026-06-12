-- Settlement reconciliation: persist each run of the nightly match between
-- OUR money-received ledger rows and InnBucks' code mini-statement, so the
-- morning report and its discrepancies are auditable/queryable rather than
-- log lines. Append-only in practice (runs are never updated).
CREATE TABLE recon_run (
    id                   UUID PRIMARY KEY,
    window_start         TIMESTAMP WITH TIME ZONE NOT NULL,
    window_end           TIMESTAMP WITH TIME ZONE NOT NULL,
    source               VARCHAR(32) NOT NULL,
    status               VARCHAR(16) NOT NULL CHECK (status IN ('CLEAN', 'DISCREPANT', 'FAILED')),
    coverage_complete    BOOLEAN NOT NULL,
    matched_count        INTEGER NOT NULL,
    matched_amount_cents BIGINT NOT NULL,
    ours_not_theirs      INTEGER NOT NULL,
    theirs_not_ours      INTEGER NOT NULL,
    amount_mismatches    INTEGER NOT NULL,
    discrepancy_detail   VARCHAR(4000),
    error                VARCHAR(1000),
    created_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_recon_run_created ON recon_run (created_at DESC);
