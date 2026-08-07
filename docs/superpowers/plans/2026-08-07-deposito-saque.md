# Depósito e Saque — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Permitir que dinheiro entre e saia do sistema via `POST /api/v1/accounts/{id}/deposit` e `/withdraw`, modelados como transferência contra uma conta de compensação por moeda.

**Architecture:** Um depósito é uma transferência da conta de compensação (`is_system = TRUE`) para a conta do usuário; um saque é o inverso. O miolo do `TransactionService.transfer` é extraído para um método privado `executeTransfer` que os três fluxos compartilham, herdando lock pessimista ordenado por UUID, idempotência, ledger de partida dobrada e notificação. O ledger continua fechando em zero.

**Tech Stack:** Java 21, Spring Boot 3.3, Spring Data JPA, PostgreSQL 16, Flyway, JUnit 5, Testcontainers, MockMvc.

**Spec:** `docs/superpowers/specs/2026-08-07-deposito-saque-design.md`

## Global Constraints

- Branch de trabalho: `feat/deposit-withdraw` (já criada, spec já commitado).
- **Docker precisa estar rodando** — toda a suíte usa Testcontainers (PostgreSQL + RabbitMQ reais). Sem Docker, `mvn test` falha na subida do container, não no código.
- Flyway gerencia todo o schema. Nenhuma alteração via `ddl-auto`. A migration nova é `V8__add_system_accounts.sql` e é imutável depois de aplicada.
- DTOs são `record`. Entidades nunca são expostas diretamente na API.
- Erros de negócio usam `ApiException(HttpStatus, String)`, tratada por `GlobalExceptionHandler`.
- `SecurityConfig` termina com `anyRequest().authenticated()` — endpoints novos sob `/api/v1/accounts` já ficam protegidos, **não altere SecurityConfig**.
- O seed do usuário de sistema deve usar `role = 'USER'`. O enum `User.Role` só tem `{USER, ADMIN}` (`User.java:83`); qualquer outro valor quebra o `@Enumerated(EnumType.STRING)` ao carregar a entidade.
- Comandos de teste rodam da raiz do projeto: `/Users/rodrigoscharp/Dev/WalletCore`.

## Baseline conhecido

Antes da Task 1, rode a suíte e registre o estado. O esperado é que **2 testes já falhem** por causa do bug que este plano corrige:

```bash
mvn test
```

`TransferIntegrationTest.transfer_withSufficientBalance_shouldSucceed` e
`TransferIntegrationTest.transfer_withSameIdempotencyKey_shouldReturnSameResult` devem falhar com
422 onde esperam 201, porque o helper `createAccount` (`TransferIntegrationTest.java:124`) ignora o
argumento `initialBalance`. Eles só ficam verdes na **Task 6**. Nas Tasks 1 a 5, "suíte verde"
significa "os mesmos 2 vermelhos de sempre, nenhum novo".

---

### Task 1: Conta de compensação no schema e na entidade

**Files:**
- Create: `src/main/resources/db/migration/V8__add_system_accounts.sql`
- Modify: `src/main/java/com/walletcore/account/entity/Account.java`
- Modify: `src/main/java/com/walletcore/account/repository/AccountRepository.java`
- Create test: `src/test/java/com/walletcore/account/ClearingAccountIntegrationTest.java`
- Create test: `src/test/java/com/walletcore/account/AccountDebitTest.java`

**Interfaces:**
- Produces: `Account.isSystem()` → `boolean`; `AccountRepository.findByCurrencyAndIsSystemTrue(String currency)` → `Optional<Account>`.

**Nota de cobertura:** o ramo de `debit` que permite saldo negativo em conta de sistema não é
testável nesta task (nada ainda deposita). Ele é provado na Task 6, que assere saldo negativo na
compensação após um depósito. Aqui testamos apenas a regressão: conta comum continua rejeitando.

- [ ] **Step 1: Write the failing tests**

`src/test/java/com/walletcore/account/AccountDebitTest.java`:

```java
package com.walletcore.account;

import com.walletcore.account.entity.Account;
import com.walletcore.user.entity.User;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class AccountDebitTest {

    @Test
    void debit_onRegularAccountWithoutBalance_shouldThrow() {
        var account = new Account(new User(), "Conta Comum", "BRL");

        assertThrows(IllegalStateException.class,
                () -> account.debit(new BigDecimal("10.00")));
    }

    @Test
    void isSystem_defaultsToFalse() {
        var account = new Account(new User(), "Conta Comum", "BRL");

        assertFalse(account.isSystem());
    }
}
```

`src/test/java/com/walletcore/account/ClearingAccountIntegrationTest.java`:

```java
package com.walletcore.account;

import com.walletcore.AbstractIntegrationTest;
import com.walletcore.account.repository.AccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class ClearingAccountIntegrationTest extends AbstractIntegrationTest {

    @Autowired AccountRepository accountRepository;

    @Test
    void migration_shouldSeedBrlClearingAccount() {
        var clearing = accountRepository.findByCurrencyAndIsSystemTrue("BRL");

        assertTrue(clearing.isPresent(), "Conta de compensação BRL deve existir");
        assertTrue(clearing.get().isSystem());
        assertEquals("BRL", clearing.get().getCurrency());
        assertEquals(0, clearing.get().getBalance().compareTo(BigDecimal.ZERO));
    }

    @Test
    void migration_shouldNotSeedClearingForUnsupportedCurrency() {
        assertTrue(accountRepository.findByCurrencyAndIsSystemTrue("USD").isEmpty());
    }

    @Test
    void clearingAccount_shouldNotBeVisibleToRegularUsers() throws Exception {
        var email = "clearing+" + UUID.randomUUID() + "@walletcore.com";
        var password = "Senha@1234";

        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        new RegisterRequest(email, password, "Regular User"))));

        var loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, password))))
                .andReturn();
        var token = objectMapper.readTree(loginResult.getResponse().getContentAsString())
                .get("accessToken").asText();

        // Não aparece na listagem do usuário
        mockMvc.perform(get("/api/v1/accounts")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());

        // E acessá-la diretamente é 403
        var clearingId = accountRepository.findByCurrencyAndIsSystemTrue("BRL").orElseThrow().getId();
        mockMvc.perform(get("/api/v1/accounts/" + clearingId + "/balance")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }
}
```

Imports adicionais para este arquivo de teste:

```java
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletcore.auth.dto.LoginRequest;
import com.walletcore.auth.dto.RegisterRequest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
```

E os campos correspondentes na classe:

```java
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -Dtest=AccountDebitTest,ClearingAccountIntegrationTest`
Expected: FAIL na compilação — `isSystem()` e `findByCurrencyAndIsSystemTrue` não existem.

- [ ] **Step 3: Write the migration**

`src/main/resources/db/migration/V8__add_system_accounts.sql`:

```sql
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
```

- [ ] **Step 4: Add the field to the entity**

Em `Account.java`, adicione o campo depois de `status` (por volta da linha 32):

```java
    @Column(name = "is_system", nullable = false)
    private boolean isSystem = false;
```

Adicione o getter junto dos outros:

```java
    public boolean isSystem() { return isSystem; }
```

Substitua `debit` (`Account.java:59-65`):

```java
    public void debit(BigDecimal amount) {
        if (!isSystem && this.balance.compareTo(amount) < 0) {
            throw new IllegalStateException("Insufficient balance");
        }
        this.balance = this.balance.subtract(amount);
        this.updatedAt = Instant.now();
    }
```

- [ ] **Step 5: Add the repository lookup**

Em `AccountRepository.java`, adicione dentro da interface:

```java
    Optional<Account> findByCurrencyAndIsSystemTrue(String currency);
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `mvn test -Dtest=AccountDebitTest,ClearingAccountIntegrationTest`
Expected: PASS, 5 testes (2 unitários + 3 de integração).

- [ ] **Step 7: Run the full suite**

Run: `mvn test`
Expected: apenas os 2 vermelhos conhecidos do baseline. Nenhum novo.

- [ ] **Step 8: Commit**

```bash
git add src/main/resources/db/migration/V8__add_system_accounts.sql \
        src/main/java/com/walletcore/account/entity/Account.java \
        src/main/java/com/walletcore/account/repository/AccountRepository.java \
        src/test/java/com/walletcore/account/AccountDebitTest.java \
        src/test/java/com/walletcore/account/ClearingAccountIntegrationTest.java
git commit -m "feat: add system clearing account for external money flow"
```

---

### Task 2: Rejeitar criação de conta em moeda sem compensação

**Files:**
- Modify: `src/main/java/com/walletcore/account/service/AccountService.java`
- Modify test: `src/test/java/com/walletcore/account/AccountIntegrationTest.java`

**Interfaces:**
- Consumes: `AccountRepository.findByCurrencyAndIsSystemTrue` (Task 1).
- Produces: `AccountService.findClearingAccount(String currency)` → `Account`, lança 422 se não houver compensação. Usado pela Task 5.

- [ ] **Step 1: Write the failing test**

Adicione a `AccountIntegrationTest.java`. O teste precisa de um token — reutilize o padrão de
autenticação já presente no arquivo (registrar + login, guardando `accessToken`).

```java
    @Test
    void createAccount_withUnsupportedCurrency_shouldReturn422() throws Exception {
        mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateAccountRequest("Conta Dólar", "USD"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("Currency not supported: USD"));
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=AccountIntegrationTest#createAccount_withUnsupportedCurrency_shouldReturn422`
Expected: FAIL — retorna 201 em vez de 422.

- [ ] **Step 3: Implement the lookup and the validation**

Em `AccountService.java`, adicione o método público (perto de `findAccountOwnedBy`):

```java
    public Account findClearingAccount(String currency) {
        return accountRepository.findByCurrencyAndIsSystemTrue(currency)
                .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Currency not supported: " + currency));
    }
```

E em `createAccount` (`AccountService.java:39-46`), valide antes de construir a conta:

```java
    @Transactional
    @CacheEvict(value = "accounts", key = "#result.userId()")
    public AccountResponse createAccount(CreateAccountRequest request) {
        var user = currentUser();
        findClearingAccount(request.currency());   // 422 se a moeda não for suportada

        var account = new Account(user, request.name(), request.currency());
        accountRepository.save(account);

        log.info("Account created: {} for user: {}", account.getId(), user.getEmail());
        return AccountResponse.from(account);
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=AccountIntegrationTest`
Expected: PASS, incluindo os testes que já existiam no arquivo.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/walletcore/account/service/AccountService.java \
        src/test/java/com/walletcore/account/AccountIntegrationTest.java
git commit -m "feat: reject account creation for currencies without a clearing account"
```

---

### Task 3: Extrair `executeTransfer` (refatoração pura)

Refatoração sem mudança de comportamento. Nenhum teste novo — o critério é que os testes
existentes continuem exatamente como estavam.

**Files:**
- Modify: `src/main/java/com/walletcore/transaction/service/TransactionService.java:46-103`

**Interfaces:**
- Produces: `private TransactionResponse executeTransfer(UUID sourceId, UUID targetId, UUID ownedAccountId, BigDecimal amount, Transaction.TransactionType type, String idempotencyKey, String description)`. Consumido pelas Tasks 4 e 5.

- [ ] **Step 1: Replace `transfer` with a thin method plus the extracted core**

Substitua o método `transfer` inteiro (`TransactionService.java:46-103`) por:

```java
    @Transactional
    public TransactionResponse transfer(TransferRequest request, String idempotencyKey) {
        return executeTransfer(
                request.sourceAccountId(),
                request.targetAccountId(),
                request.sourceAccountId(),
                request.amount(),
                Transaction.TransactionType.TRANSFER,
                idempotencyKey,
                request.description());
    }

    private TransactionResponse executeTransfer(UUID sourceId, UUID targetId, UUID ownedAccountId,
                                                BigDecimal amount, Transaction.TransactionType type,
                                                String idempotencyKey, String description) {
        // Idempotência: retorna transação existente se a chave já foi processada
        var existing = transactionRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            log.info("Idempotent request, returning existing tx: {}", idempotencyKey);
            return TransactionResponse.from(existing.get());
        }

        var user = accountService.currentUser();

        if (sourceId.equals(targetId)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Source and target accounts must be different");
        }

        // Garante ordem de lock consistente (menor UUID primeiro) para evitar deadlock
        var firstLockId  = sourceId.compareTo(targetId) < 0 ? sourceId : targetId;
        var secondLockId = sourceId.compareTo(targetId) < 0 ? targetId : sourceId;

        var firstAccount  = accountRepository.findByIdWithLock(firstLockId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Account not found"));
        var secondAccount = accountRepository.findByIdWithLock(secondLockId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Account not found"));

        var source = firstLockId.equals(sourceId) ? firstAccount : secondAccount;
        var target = firstLockId.equals(targetId) ? firstAccount : secondAccount;

        // No depósito a conta que precisa pertencer ao usuário é a de destino
        accountService.findAccountOwnedBy(ownedAccountId, user);

        if (!source.isSystem() && source.getBalance().compareTo(amount) < 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Insufficient balance to complete the transfer");
        }

        var transaction = Transaction.create(
                source, target, amount, source.getCurrency(),
                type, idempotencyKey, description);
        transactionRepository.save(transaction);

        source.debit(amount);
        target.credit(amount);

        ledgerService.recordTransfer(transaction, source, target, amount);
        transaction.complete();

        log.info("{} completed: {} -> {} amount={} tx={}",
                type, source.getId(), target.getId(), amount, transaction.getId());

        notificationProducer.publishTransferEvent(transaction, user);

        return TransactionResponse.from(transaction);
    }
```

Adicione o import de `BigDecimal` no topo do arquivo:

```java
import java.math.BigDecimal;
```

- [ ] **Step 2: Run the full suite**

Run: `mvn test`
Expected: exatamente os 2 vermelhos do baseline. Se aparecer um terceiro, a refatoração mudou
comportamento — reverta e refaça.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/walletcore/transaction/service/TransactionService.java
git commit -m "refactor: extract executeTransfer core from TransactionService.transfer"
```

---

### Task 4: Rejeitar movimentação entre moedas diferentes

Corrige um bug pré-existente: `Transaction.create` recebe `source.getCurrency()` sem comparar com
a moeda do destino, então hoje uma transferência BRL → USD passa e é registrada como BRL.

**Files:**
- Modify: `src/main/java/com/walletcore/transaction/service/TransactionService.java`
- Create test: `src/test/java/com/walletcore/transaction/CrossCurrencyTransferIntegrationTest.java`

**Interfaces:**
- Consumes: `executeTransfer` (Task 3).

**Nota:** este teste precisa de duas contas em moedas diferentes, mas a Task 2 passou a rejeitar
criação de conta em moeda sem compensação. Crie a segunda conta de compensação (EUR) **dentro do
teste**, via `AccountRepository`, para poder criar uma conta EUR pela API.

- [ ] **Step 1: Write the failing test**

```java
package com.walletcore.transaction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletcore.AbstractIntegrationTest;
import com.walletcore.account.dto.CreateAccountRequest;
import com.walletcore.account.entity.Account;
import com.walletcore.account.repository.AccountRepository;
import com.walletcore.auth.dto.LoginRequest;
import com.walletcore.auth.dto.RegisterRequest;
import com.walletcore.transaction.dto.TransferRequest;
import com.walletcore.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CrossCurrencyTransferIntegrationTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired AccountRepository accountRepository;
    @Autowired UserRepository userRepository;

    String accessToken;

    @BeforeEach
    void setUp() throws Exception {
        // Compensação EUR, criada só para viabilizar uma conta EUR pela API.
        // is_system não tem setter na entidade (é imutável do ponto de vista do
        // domínio), então marcamos via JdbcTemplate, que roda fora de transação.
        if (accountRepository.findByCurrencyAndIsSystemTrue("EUR").isEmpty()) {
            var systemUser = userRepository.findByEmail("system@walletcore.internal").orElseThrow();
            var clearingEur = new Account(systemUser, "External Clearing EUR", "EUR");
            accountRepository.saveAndFlush(clearingEur);
            jdbcTemplate.update("UPDATE accounts SET is_system = TRUE WHERE id = ?",
                    clearingEur.getId());
        }

        var email = "cross+" + UUID.randomUUID() + "@walletcore.com";
        var password = "Senha@1234";

        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        new RegisterRequest(email, password, "Cross User"))));

        var loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, password))))
                .andReturn();

        accessToken = objectMapper.readTree(loginResult.getResponse().getContentAsString())
                .get("accessToken").asText();
    }

    @Test
    void transfer_betweenDifferentCurrencies_shouldReturn422() throws Exception {
        var brlAccount = createAccount("Conta BRL", "BRL");
        var eurAccount = createAccount("Conta EUR", "EUR");

        var request = new TransferRequest(brlAccount, eurAccount,
                new BigDecimal("10.00"), "Cross currency");

        mockMvc.perform(post("/api/v1/transactions/transfer")
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message")
                        .value("Source and target accounts must have the same currency"));
    }

    private UUID createAccount(String name, String currency) throws Exception {
        var result = mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateAccountRequest(name, currency))))
                .andReturn();

        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id").asText());
    }
}
```

Declare o `JdbcTemplate` junto dos outros campos da classe de teste (ele é autoconfigurado — o
starter de JPA já traz `spring-jdbc` no classpath):

```java
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
```

Não use `@Transactional` num método auxiliar da própria classe de teste para isso: chamada interna
não passa pelo proxy do Spring, então a anotação seria ignorada silenciosamente.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=CrossCurrencyTransferIntegrationTest`
Expected: FAIL — retorna 422 de saldo insuficiente ou 201, não a mensagem de moeda.

- [ ] **Step 3: Add the currency check**

Em `executeTransfer`, logo depois da checagem de posse (`accountService.findAccountOwnedBy(...)`) e
**antes** da checagem de saldo:

```java
        if (!source.getCurrency().equals(target.getCurrency())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Source and target accounts must have the same currency");
        }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=CrossCurrencyTransferIntegrationTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/walletcore/transaction/service/TransactionService.java \
        src/test/java/com/walletcore/transaction/CrossCurrencyTransferIntegrationTest.java
git commit -m "fix: reject transfers between accounts of different currencies"
```

---

### Task 5: Endpoints de depósito e saque

**Files:**
- Create: `src/main/java/com/walletcore/transaction/dto/AmountRequest.java`
- Modify: `src/main/java/com/walletcore/transaction/service/TransactionService.java`
- Modify: `src/main/java/com/walletcore/account/controller/AccountController.java`
- Create test: `src/test/java/com/walletcore/transaction/DepositWithdrawIntegrationTest.java`

**Interfaces:**
- Consumes: `executeTransfer` (Task 3), `AccountService.findClearingAccount` (Task 2).
- Produces: `TransactionService.deposit(UUID accountId, AmountRequest request, String idempotencyKey)` → `TransactionResponse`; `TransactionService.withdraw(...)` com a mesma assinatura. Ambos consumidos pela Task 7.

- [ ] **Step 1: Write the failing test**

```java
package com.walletcore.transaction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletcore.AbstractIntegrationTest;
import com.walletcore.account.dto.CreateAccountRequest;
import com.walletcore.auth.dto.LoginRequest;
import com.walletcore.auth.dto.RegisterRequest;
import com.walletcore.transaction.dto.AmountRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DepositWithdrawIntegrationTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    String accessToken;
    UUID accountId;

    @BeforeEach
    void setUp() throws Exception {
        accessToken = registerAndLogin("deposit");
        accountId = createAccount(accessToken, "Conta Principal");
    }

    @Test
    void deposit_shouldIncreaseBalance() throws Exception {
        deposit(accessToken, accountId, "250.00", UUID.randomUUID().toString())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("DEPOSIT"))
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        mockMvc.perform(get("/api/v1/accounts/" + accountId + "/balance")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(250.00));
    }

    @Test
    void withdraw_withSufficientBalance_shouldDecreaseBalance() throws Exception {
        deposit(accessToken, accountId, "300.00", UUID.randomUUID().toString())
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/accounts/" + accountId + "/withdraw")
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new AmountRequest(new BigDecimal("100.00"), "Saque"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("WITHDRAWAL"));

        mockMvc.perform(get("/api/v1/accounts/" + accountId + "/balance")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(jsonPath("$.balance").value(200.00));
    }

    @Test
    void withdraw_withoutBalance_shouldReturn422() throws Exception {
        mockMvc.perform(post("/api/v1/accounts/" + accountId + "/withdraw")
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new AmountRequest(new BigDecimal("50.00"), "Saque sem saldo"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message")
                        .value("Insufficient balance to complete the transfer"));
    }

    @Test
    void deposit_intoAnotherUsersAccount_shouldReturn403() throws Exception {
        var otherToken = registerAndLogin("other");

        deposit(otherToken, accountId, "10.00", UUID.randomUUID().toString())
                .andExpect(status().isForbidden());
    }

    @Test
    void deposit_withSameIdempotencyKey_shouldCreditOnce() throws Exception {
        var key = UUID.randomUUID().toString();

        var first = deposit(accessToken, accountId, "80.00", key)
                .andExpect(status().isCreated()).andReturn();
        var second = deposit(accessToken, accountId, "80.00", key)
                .andExpect(status().isCreated()).andReturn();

        var firstId = objectMapper.readTree(first.getResponse().getContentAsString()).get("id").asText();
        var secondId = objectMapper.readTree(second.getResponse().getContentAsString()).get("id").asText();
        assertEquals(firstId, secondId, "Chave idempotente deve devolver a mesma transação");

        mockMvc.perform(get("/api/v1/accounts/" + accountId + "/balance")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(jsonPath("$.balance").value(80.00));
    }

    @Test
    void deposit_withNonPositiveAmount_shouldReturn400() throws Exception {
        deposit(accessToken, accountId, "0.00", UUID.randomUUID().toString())
                .andExpect(status().isBadRequest());
    }

    private org.springframework.test.web.servlet.ResultActions deposit(
            String token, UUID account, String amount, String idempotencyKey) throws Exception {
        return mockMvc.perform(post("/api/v1/accounts/" + account + "/deposit")
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        new AmountRequest(new BigDecimal(amount), "Depósito"))));
    }

    private String registerAndLogin(String prefix) throws Exception {
        var email = prefix + "+" + UUID.randomUUID() + "@walletcore.com";
        var password = "Senha@1234";

        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        new RegisterRequest(email, password, "Test User"))));

        var loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, password))))
                .andReturn();

        return objectMapper.readTree(loginResult.getResponse().getContentAsString())
                .get("accessToken").asText();
    }

    private UUID createAccount(String token, String name) throws Exception {
        var result = mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateAccountRequest(name, "BRL"))))
                .andReturn();

        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id").asText());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=DepositWithdrawIntegrationTest`
Expected: FAIL na compilação — `AmountRequest` não existe.

- [ ] **Step 3: Create the request DTO**

`src/main/java/com/walletcore/transaction/dto/AmountRequest.java`:

```java
package com.walletcore.transaction.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record AmountRequest(
        @NotNull @DecimalMin(value = "0.01", message = "Amount must be greater than zero")
        BigDecimal amount,

        @Size(max = 255) String description
) {}
```

- [ ] **Step 4: Add the service methods**

Em `TransactionService.java`, adicione depois de `transfer`:

```java
    @Transactional
    public TransactionResponse deposit(UUID accountId, AmountRequest request, String idempotencyKey) {
        var clearing = resolveClearingFor(accountId);
        return executeTransfer(clearing.getId(), accountId, accountId, request.amount(),
                Transaction.TransactionType.DEPOSIT, idempotencyKey, request.description());
    }

    @Transactional
    public TransactionResponse withdraw(UUID accountId, AmountRequest request, String idempotencyKey) {
        var clearing = resolveClearingFor(accountId);
        return executeTransfer(accountId, clearing.getId(), accountId, request.amount(),
                Transaction.TransactionType.WITHDRAWAL, idempotencyKey, request.description());
    }

    private Account resolveClearingFor(UUID accountId) {
        var account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Account not found"));
        return accountService.findClearingAccount(account.getCurrency());
    }
```

Adicione os imports necessários no topo:

```java
import com.walletcore.account.entity.Account;
import com.walletcore.transaction.dto.AmountRequest;
```

- [ ] **Step 5: Add the endpoints**

Em `AccountController.java`, injete `TransactionService` e adicione os dois endpoints. O construtor
passa a ser:

```java
    private final AccountService accountService;
    private final TransactionService transactionService;

    public AccountController(AccountService accountService,
                             TransactionService transactionService) {
        this.accountService = accountService;
        this.transactionService = transactionService;
    }
```

E os métodos:

```java
    @PostMapping("/{id}/deposit")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Deposit funds into an account")
    public TransactionResponse deposit(
            @PathVariable UUID id,
            @Valid @RequestBody AmountRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {

        return transactionService.deposit(id, request, idempotencyKey);
    }

    @PostMapping("/{id}/withdraw")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Withdraw funds from an account")
    public TransactionResponse withdraw(
            @PathVariable UUID id,
            @Valid @RequestBody AmountRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {

        return transactionService.withdraw(id, request, idempotencyKey);
    }
```

Imports novos em `AccountController.java`:

```java
import com.walletcore.transaction.dto.AmountRequest;
import com.walletcore.transaction.dto.TransactionResponse;
import com.walletcore.transaction.service.TransactionService;
```

- [ ] **Step 6: Run test to verify it passes**

Run: `mvn test -Dtest=DepositWithdrawIntegrationTest`
Expected: PASS, 6 testes.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/walletcore/transaction/dto/AmountRequest.java \
        src/main/java/com/walletcore/transaction/service/TransactionService.java \
        src/main/java/com/walletcore/account/controller/AccountController.java \
        src/test/java/com/walletcore/transaction/DepositWithdrawIntegrationTest.java
git commit -m "feat: add deposit and withdrawal endpoints"
```

---

### Task 6: Provar a partida dobrada e destravar a suíte

Esta é a task que deixa `mvn test` inteiramente verde pela primeira vez.

**Files:**
- Modify test: `src/test/java/com/walletcore/transaction/TransferIntegrationTest.java:124-134`
- Modify test: `src/test/java/com/walletcore/transaction/DepositWithdrawIntegrationTest.java`

**Interfaces:**
- Consumes: endpoint de depósito (Task 5), `LedgerEntryRepository.findAllByTransactionIdOrderByCreatedAtAsc` (já existe).

- [ ] **Step 1: Write the failing ledger test**

Adicione a `DepositWithdrawIntegrationTest.java`:

```java
    @Autowired com.walletcore.ledger.repository.LedgerEntryRepository ledgerEntryRepository;
    @Autowired com.walletcore.account.repository.AccountRepository accountRepository;

    @Test
    void deposit_shouldRecordBalancedDoubleEntry() throws Exception {
        var result = deposit(accessToken, accountId, "120.00", UUID.randomUUID().toString())
                .andExpect(status().isCreated()).andReturn();

        var txId = UUID.fromString(objectMapper
                .readTree(result.getResponse().getContentAsString()).get("id").asText());

        var entries = ledgerEntryRepository.findAllByTransactionIdOrderByCreatedAtAsc(txId);

        assertEquals(2, entries.size(), "Depósito deve gerar dois lançamentos");

        var debits = entries.stream()
                .filter(e -> e.getEntryType() == com.walletcore.ledger.entity.LedgerEntry.EntryType.DEBIT)
                .map(com.walletcore.ledger.entity.LedgerEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        var credits = entries.stream()
                .filter(e -> e.getEntryType() == com.walletcore.ledger.entity.LedgerEntry.EntryType.CREDIT)
                .map(com.walletcore.ledger.entity.LedgerEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertEquals(0, credits.subtract(debits).compareTo(BigDecimal.ZERO),
                "Soma dos créditos deve igualar a dos débitos");
    }

    @Test
    void deposit_shouldDriveClearingAccountNegative() throws Exception {
        var before = accountRepository.findByCurrencyAndIsSystemTrue("BRL").orElseThrow().getBalance();

        deposit(accessToken, accountId, "40.00", UUID.randomUUID().toString())
                .andExpect(status().isCreated());

        var after = accountRepository.findByCurrencyAndIsSystemTrue("BRL").orElseThrow().getBalance();

        assertEquals(0, before.subtract(new BigDecimal("40.00")).compareTo(after),
                "Compensação deve ficar 40 mais negativa após o depósito");
    }
```

- [ ] **Step 2: Run tests to verify they pass**

Run: `mvn test -Dtest=DepositWithdrawIntegrationTest`
Expected: PASS, 8 testes. Estes dois já devem passar — a implementação da Task 5 os satisfaz. Eles
existem como prova explícita da decisão central do spec e do ramo de `debit` da Task 1.

- [ ] **Step 3: Fix the transfer test helper**

Em `TransferIntegrationTest.java`, substitua o helper `createAccount` (linhas 124-134), que hoje
ignora o parâmetro `initialBalance`:

```java
    private UUID createAccount(String name, String initialBalance) throws Exception {
        var createRequest = new CreateAccountRequest(name, "BRL");
        var result = mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andReturn();

        var body = objectMapper.readTree(result.getResponse().getContentAsString());
        var accountId = UUID.fromString(body.get("id").asText());

        var amount = new BigDecimal(initialBalance);
        if (amount.compareTo(BigDecimal.ZERO) > 0) {
            mockMvc.perform(post("/api/v1/accounts/" + accountId + "/deposit")
                            .header("Authorization", "Bearer " + accessToken)
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new AmountRequest(amount, "Saldo inicial"))))
                    .andExpect(status().isCreated());
        }

        return accountId;
    }
```

Adicione o import:

```java
import com.walletcore.transaction.dto.AmountRequest;
```

- [ ] **Step 4: Run the full suite**

Run: `mvn test`
Expected: **PASS, zero falhas.** Os 2 vermelhos do baseline ficam verdes aqui. Se
`transfer_withSufficientBalance_shouldSucceed` continuar vermelho, confira se o depósito no helper
está sendo feito antes da transferência e se o valor bate.

- [ ] **Step 5: Commit**

```bash
git add src/test/java/com/walletcore/transaction/TransferIntegrationTest.java \
        src/test/java/com/walletcore/transaction/DepositWithdrawIntegrationTest.java
git commit -m "test: prove balanced double-entry and seed transfer test balances via deposit"
```

---

### Task 7: Estender o rate limit às três operações

**Files:**
- Modify: `src/main/java/com/walletcore/ratelimit/config/RateLimitService.java`
- Modify: `src/main/java/com/walletcore/transaction/controller/TransactionController.java:47`
- Modify: `src/main/java/com/walletcore/account/controller/AccountController.java`

**Interfaces:**
- Produces: `RateLimitService.checkOperationLimit(String userEmail)` — substitui `checkTransferLimit`.

**Nota:** o prefixo de configuração continua `walletcore.rate-limit.transfer`
(`RateLimitProperties.java:7`, `application.yml:78-82`). Renomear o prefixo exigiria mudar o YAML
sem ganho funcional; fica como está.

- [ ] **Step 1: Rename the method**

Em `RateLimitService.java`, renomeie `checkTransferLimit` para `checkOperationLimit`. O corpo não
muda:

```java
    public void checkOperationLimit(String userEmail) {
        var bucket = buckets.computeIfAbsent(userEmail, this::newBucket);
        if (!bucket.tryConsume(1)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                    String.format("Rate limit exceeded: max %d operations per %d minute(s)",
                            properties.getCapacity(), properties.getRefillDurationMinutes()));
        }
    }
```

- [ ] **Step 2: Update the transfer call site**

Em `TransactionController.java:47`, troque:

```java
        rateLimitService.checkOperationLimit(userDetails.getUsername());
```

- [ ] **Step 3: Apply it to deposit and withdraw**

Em `AccountController.java`, o construtor passa a receber três dependências:

```java
    private final AccountService accountService;
    private final TransactionService transactionService;
    private final RateLimitService rateLimitService;

    public AccountController(AccountService accountService,
                             TransactionService transactionService,
                             RateLimitService rateLimitService) {
        this.accountService = accountService;
        this.transactionService = transactionService;
        this.rateLimitService = rateLimitService;
    }
```

E os dois endpoints ganham o principal e a checagem, no mesmo padrão de
`TransactionController.transfer`:

```java
    @PostMapping("/{id}/deposit")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Deposit funds into an account")
    public TransactionResponse deposit(
            @PathVariable UUID id,
            @Valid @RequestBody AmountRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal UserDetails userDetails) {

        rateLimitService.checkOperationLimit(userDetails.getUsername());
        return transactionService.deposit(id, request, idempotencyKey);
    }

    @PostMapping("/{id}/withdraw")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Withdraw funds from an account")
    public TransactionResponse withdraw(
            @PathVariable UUID id,
            @Valid @RequestBody AmountRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal UserDetails userDetails) {

        rateLimitService.checkOperationLimit(userDetails.getUsername());
        return transactionService.withdraw(id, request, idempotencyKey);
    }
```

Imports novos:

```java
import com.walletcore.ratelimit.config.RateLimitService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
```

- [ ] **Step 4: Run the full suite**

Run: `mvn test`
Expected: PASS, zero falhas. O balde é por e-mail e cada teste registra um usuário novo com UUID no
endereço, então nenhum teste chega perto do limite de 10/min.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/walletcore/ratelimit/config/RateLimitService.java \
        src/main/java/com/walletcore/transaction/controller/TransactionController.java \
        src/main/java/com/walletcore/account/controller/AccountController.java
git commit -m "feat: apply per-user rate limit to deposit and withdrawal"
```

---

### Task 8: Documentação

**Files:**
- Modify: `README.md:206-212` (tabela de Contas), `README.md:325-329` (tabela de testes)

- [ ] **Step 1: Add the endpoints to the accounts table**

Em `README.md`, na tabela da seção `### Contas`, adicione duas linhas:

```markdown
| `POST` | `/api/v1/accounts/{id}/deposit` | JWT + Idempotency-Key | Deposita valor na conta |
| `POST` | `/api/v1/accounts/{id}/withdraw` | JWT + Idempotency-Key | Saca valor da conta |
```

- [ ] **Step 2: Add the new tests to the tests table**

Na tabela da seção `## Testes`:

```markdown
| `DepositWithdrawIntegrationTest` | Depósito, saque, saldo insuficiente, conta alheia, idempotência, partida dobrada |
| `ClearingAccountIntegrationTest` | Seed da conta de compensação BRL |
| `CrossCurrencyTransferIntegrationTest` | Transferência entre moedas diferentes é rejeitada |
```

- [ ] **Step 3: Document the clearing account decision**

Na seção `## Decisões Técnicas`, adicione:

```markdown
**Conta de compensação para dinheiro externo**
Depósito e saque não têm contraparte interna. Em vez de tornar `source_account_id` e
`target_account_id` nulos — o que quebraria a partida dobrada — o dinheiro que entra vem de uma
conta de compensação por moeda, marcada com `is_system`. Ela é a única que pode ficar negativa, e
seu saldo é exatamente o total que o sistema deve ao mundo externo. A soma de todos os lançamentos
do ledger continua sendo zero.
```

- [ ] **Step 4: Commit**

```bash
git add README.md
git commit -m "docs: document deposit, withdrawal and the clearing account"
```

---

## Verificação final

- [ ] `mvn test` — suíte inteira verde, incluindo os 2 testes que estavam vermelhos no baseline.
- [ ] `mvn -DskipTests package` — build limpo.
- [ ] Fluxo manual com a app rodando (`docker compose up -d && mvn spring-boot:run`): registrar,
      criar conta, depositar 100, conferir saldo 100, transferir 40 para uma segunda conta,
      conferir saldos 60 e 40, sacar 60, conferir saldo 0.
