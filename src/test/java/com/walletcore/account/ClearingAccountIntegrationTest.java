package com.walletcore.account;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletcore.AbstractIntegrationTest;
import com.walletcore.account.dto.CreateAccountRequest;
import com.walletcore.account.repository.AccountRepository;
import com.walletcore.auth.dto.LoginRequest;
import com.walletcore.auth.dto.RegisterRequest;
import com.walletcore.transaction.dto.AmountRequest;
import com.walletcore.transaction.dto.TransferRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ClearingAccountIntegrationTest extends AbstractIntegrationTest {

    @Autowired AccountRepository accountRepository;
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void migration_shouldSeedBrlClearingAccount() {
        var clearing = accountRepository.findByCurrencyAndIsSystemTrue("BRL");

        assertTrue(clearing.isPresent(), "Conta de compensação BRL deve existir");
        assertTrue(clearing.get().isSystem());
        assertEquals("BRL", clearing.get().getCurrency());
        // Sem asserção de saldo: os containers de teste (Postgres) são singleton e o banco é
        // compartilhado entre classes de teste, então o saldo da compensação reflete qualquer
        // depósito/saque já executado por outras classes na mesma JVM. Não há estado "imaculado"
        // garantido aqui — só a presença, a flag de sistema e a moeda são invariantes reais da
        // migration.
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

    /**
     * O id da compensação não é secreto: ele volta como {@code sourceAccountId} no corpo de toda
     * resposta de depósito. Sem esta regra, um usuário poderia transferir para lá por
     * {@code POST /transactions/transfer} — ele é dono da origem, as moedas batem, origem ≠ destino
     * e o saldo cobre — e o saldo da compensação, que deveria ser exatamente o que o sistema deve
     * ao mundo externo, passaria a ser mexido por transferências internas gravadas como TRANSFER.
     *
     * <p>A resposta é 404, e não 422, para não confirmar ao chamador o que aquele id é.
     */
    @Test
    void transfer_targetingClearingAccount_shouldReturn404() throws Exception {
        var token = registerAndLogin("transfer-to-clearing");
        var accountId = createAccount(token, "Conta Origem");

        mockMvc.perform(post("/api/v1/accounts/" + accountId + "/deposit")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new AmountRequest(new BigDecimal("500.00"), "Saldo inicial"))))
                .andExpect(status().isCreated());

        var clearingId = accountRepository.findByCurrencyAndIsSystemTrue("BRL").orElseThrow().getId();
        var clearingBalanceBefore = accountRepository
                .findByCurrencyAndIsSystemTrue("BRL").orElseThrow().getBalance();

        mockMvc.perform(post("/api/v1/transactions/transfer")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TransferRequest(
                                accountId, clearingId, new BigDecimal("100.00"), "Para a compensação"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Account not found"));

        // Nem a origem nem a compensação se mexeram
        mockMvc.perform(get("/api/v1/accounts/" + accountId + "/balance")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.balance").value(500.00));

        assertEquals(0, clearingBalanceBefore.compareTo(accountRepository
                        .findByCurrencyAndIsSystemTrue("BRL").orElseThrow().getBalance()),
                "Saldo da compensação não pode ser movido por uma TRANSFER");
    }

    private String registerAndLogin(String prefix) throws Exception {
        var email = prefix + "+" + UUID.randomUUID() + "@walletcore.com";
        var password = "Senha@1234";

        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        new RegisterRequest(email, password, "Clearing User"))));

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
