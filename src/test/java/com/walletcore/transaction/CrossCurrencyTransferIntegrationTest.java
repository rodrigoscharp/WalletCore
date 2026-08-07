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
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

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
