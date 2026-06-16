CREATE TABLE transactions (
    id                UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    source_account_id UUID           NOT NULL,
    target_account_id UUID           NOT NULL,
    amount            NUMERIC(19,4)  NOT NULL,
    currency          VARCHAR(3)     NOT NULL DEFAULT 'BRL',
    type              VARCHAR(30)    NOT NULL,
    status            VARCHAR(20)    NOT NULL DEFAULT 'PENDING',
    idempotency_key   VARCHAR(64)    NOT NULL,
    description       VARCHAR(255),
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_transactions_source FOREIGN KEY (source_account_id) REFERENCES accounts (id),
    CONSTRAINT fk_transactions_target FOREIGN KEY (target_account_id) REFERENCES accounts (id),
    CONSTRAINT chk_transactions_amount CHECK (amount > 0),
    CONSTRAINT chk_transactions_status CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED', 'REVERSED')),
    CONSTRAINT chk_transactions_type   CHECK (type IN ('TRANSFER', 'DEPOSIT', 'WITHDRAWAL')),
    CONSTRAINT uq_transactions_idempotency UNIQUE (idempotency_key)
);

CREATE INDEX idx_transactions_source_account ON transactions (source_account_id);
CREATE INDEX idx_transactions_target_account ON transactions (target_account_id);
CREATE INDEX idx_transactions_created_at     ON transactions (created_at DESC);
CREATE INDEX idx_transactions_status         ON transactions (status);
CREATE INDEX idx_transactions_idempotency    ON transactions (idempotency_key);
