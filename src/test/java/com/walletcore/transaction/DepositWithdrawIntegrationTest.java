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
    @Autowired com.walletcore.ledger.repository.LedgerEntryRepository ledgerEntryRepository;
    @Autowired com.walletcore.account.repository.AccountRepository accountRepository;

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
    void deposit_withNonPositiveAmount_shouldReturn422() throws Exception {
        deposit(accessToken, accountId, "0.00", UUID.randomUUID().toString())
                .andExpect(status().isUnprocessableEntity());
    }

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
