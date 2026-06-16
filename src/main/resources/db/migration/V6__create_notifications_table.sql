CREATE TABLE notifications (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        UUID         NOT NULL,
    transaction_id UUID,
    type           VARCHAR(50)  NOT NULL,
    status         VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    payload        TEXT,
    error_message  TEXT,
    attempts       INT          NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    sent_at        TIMESTAMPTZ,

    CONSTRAINT fk_notifications_user        FOREIGN KEY (user_id)        REFERENCES users (id),
    CONSTRAINT fk_notifications_transaction FOREIGN KEY (transaction_id) REFERENCES transactions (id),
    CONSTRAINT chk_notifications_status     CHECK (status IN ('PENDING', 'SENT', 'FAILED'))
);

CREATE INDEX idx_notifications_user_id        ON notifications (user_id);
CREATE INDEX idx_notifications_transaction_id ON notifications (transaction_id);
CREATE INDEX idx_notifications_status         ON notifications (status);
