CREATE TABLE ledger_entries (
    id             UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id UUID           NOT NULL,
    account_id     UUID           NOT NULL,
    entry_type     VARCHAR(6)     NOT NULL,
    amount         NUMERIC(19,4)  NOT NULL,
    balance_after  NUMERIC(19,4)  NOT NULL,
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_ledger_transaction FOREIGN KEY (transaction_id) REFERENCES transactions (id),
    CONSTRAINT fk_ledger_account     FOREIGN KEY (account_id)     REFERENCES accounts (id),
    CONSTRAINT chk_ledger_entry_type CHECK (entry_type IN ('DEBIT', 'CREDIT')),
    CONSTRAINT chk_ledger_amount     CHECK (amount > 0)
);

CREATE INDEX idx_ledger_transaction_id ON ledger_entries (transaction_id);
CREATE INDEX idx_ledger_account_id     ON ledger_entries (account_id);
CREATE INDEX idx_ledger_created_at     ON ledger_entries (created_at DESC);
