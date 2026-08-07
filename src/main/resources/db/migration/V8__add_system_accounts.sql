ALTER TABLE accounts ADD COLUMN is_system BOOLEAN NOT NULL DEFAULT FALSE;

-- A conta de compensação fica negativa por definição: ela representa o
-- dinheiro que entrou de fora do sistema.
ALTER TABLE accounts DROP CONSTRAINT chk_accounts_balance;
ALTER TABLE accounts ADD CONSTRAINT chk_accounts_balance
    CHECK (balance >= 0 OR is_system);

-- No máximo uma conta de compensação por moeda.
CREATE UNIQUE INDEX uq_accounts_system_currency
    ON accounts (currency) WHERE is_system;

-- Usuário de sistema: enabled = FALSE impede autenticação, e o valor em
-- password não é um hash BCrypt válido. role = 'USER' porque o enum
-- User.Role só aceita USER e ADMIN.
INSERT INTO users (email, password, full_name, role, enabled)
VALUES ('system@walletcore.internal', 'x-not-a-valid-bcrypt-hash',
        'WalletCore System', 'USER', FALSE);

INSERT INTO accounts (user_id, name, currency, balance, status, is_system)
SELECT id, 'External Clearing BRL', 'BRL', 0, 'ACTIVE', TRUE
FROM users
WHERE email = 'system@walletcore.internal';
