# Depósito e saque via conta de compensação

**Data:** 2026-08-07
**Status:** aprovado, pronto para plano de implementação

## Problema

Contas nascem com saldo zero (`Account.java:29`, construtor em `Account.java:48`) e não existe
nenhum endpoint que credite dinheiro. O enum `TransactionType` já declara `DEPOSIT` e `WITHDRAWAL`,
e o README documenta os dois como filtro de listagem, mas nenhum dos dois pode ser criado.

Consequências diretas:

- O fluxo central do produto — transferência — não é exercitável nem por `curl` nem pelos testes.
- `TransferIntegrationTest.java:124` recebe um argumento `initialBalance` e o descarta: cria a conta
  só com nome e moeda. Os testes `transfer_withSufficientBalance_shouldSucceed` e
  `transfer_withSameIdempotencyKey_shouldReturnSameResult` transferem de uma conta com saldo zero e
  devem falhar com 422 onde esperam 201.

## Restrição que define o design

Em `V4__create_transactions_table.sql`, `source_account_id` e `target_account_id` são ambos
`NOT NULL` com FK para `accounts`. Dinheiro que entra de fora do sistema não tem conta de origem
natural.

A alternativa considerada e descartada foi tornar as duas colunas nullable com um CHECK por tipo.
Descartada porque o ledger passaria a ter lançamento de uma perna só — deixaria de ser partida
dobrada de verdade, que é a característica técnica que o projeto vende — e porque espalharia
`null` por três pontos que hoje desreferenciam sem checar (`TransactionResponse.from`, e a
checagem de posse em `TransactionService.java:125-126`).

## Decisão: conta de compensação

Um depósito é uma transferência da conta de compensação para a conta do usuário. Um saque é o
inverso. O ledger continua fechando em zero: soma dos DEBIT igual à soma dos CREDIT, sempre.

---

## 1. Migration `V8__add_system_accounts.sql`

```sql
ALTER TABLE accounts ADD COLUMN is_system BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE accounts DROP CONSTRAINT chk_accounts_balance;
ALTER TABLE accounts ADD CONSTRAINT chk_accounts_balance
    CHECK (balance >= 0 OR is_system);

CREATE UNIQUE INDEX uq_accounts_system_currency
    ON accounts (currency) WHERE is_system;
```

A compensação fica com saldo negativo por definição — ela representa o dinheiro que entrou de
fora. O índice único parcial garante no máximo uma compensação por moeda.

Seed do usuário de sistema e da conta BRL:

- `users`: email `system@walletcore.internal`, `enabled = FALSE`, e um literal inválido em
  `password` (não é hash BCrypt válido). Com `enabled = FALSE` o Spring Security já rejeita a
  autenticação; o hash inválido é a segunda barreira.
- `accounts`: `name = 'External Clearing BRL'`, `currency = 'BRL'`, `is_system = TRUE`,
  `balance = 0`, pendurada no usuário de sistema.

## 2. Entidade e repositório

`Account` ganha o campo `isSystem` (`@Column(name = "is_system", nullable = false)`, default
`false`) e o getter correspondente.

`Account.debit` (`Account.java:60`) hoje joga `IllegalStateException` quando o saldo não cobre o
valor. Passa a permitir saldo negativo quando `isSystem` é verdadeiro.

`AccountRepository` ganha `Optional<Account> findByCurrencyAndIsSystemTrue(String currency)`.

## 3. Validação de moeda

`CreateAccountRequest` aceita qualquer código ISO de 3 letras (`^[A-Z]{3}$`), então hoje é possível
criar uma conta USD que nunca poderá receber depósito.

`AccountService.createAccount` passa a consultar se existe conta de compensação para a moeda
pedida. Não existe → 422 `"Currency not supported"`.

Deliberadamente **não** existe uma lista de moedas suportadas em `application.yml`: ela sairia de
sincronia com a migration na primeira mudança. O banco é a única fonte de verdade, e abrir uma
moeda nova é apenas uma migration nova.

## 4. Serviço

`TransactionService.transfer` hoje mistura duas responsabilidades: resolver quem é
origem/destino/dono, e mecanicamente mover o dinheiro. O miolo é extraído para um método privado:

```java
private TransactionResponse executeTransfer(
        UUID sourceId, UUID targetId, UUID ownedAccountId,
        BigDecimal amount, Transaction.TransactionType type,
        String idempotencyKey, String description)
```

Os três métodos públicos ficam finos:

| Método | origem | destino | `ownedAccountId` |
|---|---|---|---|
| `transfer` | do request | do request | origem |
| `deposit` | compensação da moeda da conta | conta do usuário | **destino** |
| `withdraw` | conta do usuário | compensação da moeda da conta | origem |

O parâmetro `ownedAccountId` existe porque no depósito a conta que precisa pertencer ao usuário é
a de destino, não a de origem — hoje a checagem em `TransactionService.java:79` é fixa na origem.

Preservado sem alteração: lock pessimista com ordenação por UUID, registro no ledger via
`LedgerService.recordTransfer`, e publicação do evento de notificação. Depósito e saque herdam
tudo isso.

A idempotência por `Idempotency-Key` não fica preservada sem alteração: como a chave tem UNIQUE
global na tabela `transactions`, um hit passa a ser tratado como replay só quando a transação
armazenada corresponde à requisição em tipo, conta de origem, conta de destino e valor — antes,
qualquer hit era devolvido como replay, o que faria uma chave reusada entre depósito e saque
responder 201 com o corpo da operação errada. Quando não corresponde, responde 409.

Dois pontos cedem quando a conta de origem é de sistema:

- a checagem de saldo insuficiente (`TransactionService.java:81`) é pulada;
- o `Account.debit` permite negativo, conforme §2.

A resolução da compensação acontece **antes** de entrar no `executeTransfer`, para que os dois
locks continuem sendo adquiridos por ID em ordem de UUID, sem caminho de lock adicional.

## 5. Correção de escopo adjacente: moeda cruzada

`Transaction.create` recebe `source.getCurrency()` sem nunca comparar com a moeda do destino
(`TransactionService.java:87`). Hoje uma transferência de conta BRL para conta USD passa e é
registrada como BRL.

`executeTransfer` passa a validar `source.currency == target.currency` → 422. Incluído no escopo
porque é exatamente o caminho de código sendo modificado, e porque o depósito depende de moedas
casadas. No caso de depósito e saque a igualdade é estrutural: a compensação é buscada *pela*
moeda da conta.

## 6. API

| Método | Endpoint | Autenticação | Descrição |
|---|---|---|---|
| `POST` | `/api/v1/accounts/{id}/deposit` | JWT + `Idempotency-Key` | Credita valor na conta |
| `POST` | `/api/v1/accounts/{id}/withdraw` | JWT + `Idempotency-Key` | Debita valor da conta |

Ficam como sub-recurso da conta, e não em `/transactions`, porque a operação age sobre uma conta
específica — em `/transactions` o `accountId` teria que ir no corpo.

Ambos respondem 201 com `TransactionResponse`, o mesmo contrato já devolvido pela transferência.

Corpo compartilhado pelos dois:

```java
public record AmountRequest(
        @NotNull @DecimalMin("0.01") BigDecimal amount,
        @Size(max = 255) String description
) {}
```

Os endpoints ficam em `AccountController`, que já resolve `{id}` e é onde o recurso conta vive.
A lógica permanece em `TransactionService` — `AccountController` apenas delega.

### Erros

| Código | Situação |
|---|---|
| 400 | Validação do corpo, ou header `Idempotency-Key` ausente |
| 403 | Conta pertence a outro usuário |
| 404 | Conta inexistente |
| 409 | `Idempotency-Key` já usada para uma operação diferente |
| 422 | Saldo insuficiente (saque), conta não ativa, moeda sem compensação, moedas divergentes |
| 429 | Rate limit excedido |

## 7. Rate limit

`RateLimitService.checkTransferLimit` hoje cobre apenas transferência. Passa a se chamar
`checkOperationLimit` e é aplicado às três operações que movem dinheiro, com o mesmo balde de
10/min por usuário. Limitar transferência e deixar saque livre não faz sentido — é o mesmo risco.

Nota de escopo: a implementação continua sendo `ConcurrentHashMap` em memória
(`RateLimitService.java:16`), não distribuída. Trocar por Redis é item separado do backlog e não
entra aqui.

## 8. Testes

**Correção que destrava a suíte:** o helper `createAccount` do `TransferIntegrationTest.java:124`
passa a de fato depositar o `initialBalance` que hoje ignora, chamando o endpoint novo depois de
criar a conta.

**Novo `DepositWithdrawIntegrationTest`:**

| Cenário | Asserção |
|---|---|
| Depósito em conta própria | 201, e `GET /balance` reflete o valor creditado |
| Saque com saldo suficiente | 201, e o saldo cai pelo valor sacado |
| Saque sem saldo | 422, mensagem de saldo insuficiente |
| Depósito em conta de outro usuário | 403 |
| Depósito repetido com a mesma `Idempotency-Key` | Mesmo ID de transação, saldo creditado uma vez |
| Partida dobrada | Depósito gera exatamente 2 lançamentos no ledger, DEBIT e CREDIT, somando zero |

O último caso é o que prova a decisão central deste spec e justifica ter escolhido conta de
compensação em vez de colunas nullable.

**Conta de sistema protegida:** um caso verificando que a conta de compensação não aparece em
`GET /api/v1/accounts` de nenhum usuário comum e que `findAccountOwnedBy` a rejeita.

## 9. Documentação

Atualizar as tabelas de endpoints do README (seção `Contas`) com as duas rotas novas, e a seção de
testes com o `DepositWithdrawIntegrationTest`.

## Fora de escopo

- Rate limit distribuído via Redis (a dependência `bucket4j-redis` declarada em `pom.xml:121` segue
  sem uso).
- Invalidação do cache de `listAccounts`, que serve saldo desatualizado após movimentação.
- Moedas além de BRL.
- Estorno ou reversão de depósito/saque.
