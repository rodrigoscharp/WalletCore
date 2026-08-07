-- V8 semeou a compensacao so de BRL. Um banco anterior a este branch pode ja conter contas em
-- qualquer moeda, porque CreateAccountRequest valida a moeda apenas como ^[A-Z]{3}$, sem lista
-- fechada. Sem compensacao, toda conta nessas moedas fica sem deposito e sem saque (422 Currency
-- not supported) e nenhuma conta nova pode ser aberta na moeda -- um beco sem saida silencioso.
--
-- V8 e imutavel, ja aplicada em bancos existentes, e edita-la quebraria a validacao de checksum do
-- Flyway. Por isso a correcao vem aqui, numa migration nova.
--
-- Semeia uma compensacao para cada moeda ja presente em accounts que ainda nao tenha uma,
-- reusando o usuario de sistema criado em V8. O NOT EXISTS torna o INSERT idempotente e evita
-- colisao com o indice unico parcial uq_accounts_system_currency. Num banco novo, onde V8 ja criou
-- a compensacao BRL e nao existe conta em outra moeda, esta migration e um no-op.
--
-- Sem acentos e sem ponto-e-virgula nos comentarios de proposito: o parser de SQL do Flyway
-- termina o statement no primeiro ponto-e-virgula que encontra, inclusive dentro de um comentario
-- de linha.
--
-- O alias do usuario de sistema nao pode se chamar system_user: SYSTEM_USER e palavra reservada
-- no PostgreSQL 16 (funcao niladica do SQL:2016) e o parser recusa a qualificacao com ponto.
INSERT INTO accounts (user_id, name, currency, balance, status, is_system)
SELECT sys.id,
       'External Clearing ' || existing.currency,
       existing.currency,
       0,
       'ACTIVE',
       TRUE
FROM (SELECT DISTINCT currency FROM accounts) AS existing
CROSS JOIN users AS sys
WHERE sys.email = 'system@walletcore.internal'
  AND NOT EXISTS (
        SELECT 1
        FROM accounts clearing
        WHERE clearing.is_system
          AND clearing.currency = existing.currency);
