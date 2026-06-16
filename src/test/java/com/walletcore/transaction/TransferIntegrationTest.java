package com.walletcore.transaction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletcore.AbstractIntegrationTest;
import com.walletcore.account.dto.CreateAccountRequest;
import com.walletcore.auth.dto.LoginRequest;
import com.walletcore.auth.dto.RegisterRequest;
import com.walletcore.transaction.dto.TransferRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class TransferIntegrationTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    String accessToken;
    UUID sourceAccountId;
    UUID targetAccountId;

    @BeforeEach
    void setUp() throws Exception {
        var email = "transfer+" + UUID.randomUUID() + "@walletcore.com";
        var password = "Senha@1234";

        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        new RegisterRequest(email, password, "Transfer User"))));

        var loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, password))))
                .andReturn();

        var loginBody = objectMapper.readTree(loginResult.getResponse().getContentAsString());
        accessToken = loginBody.get("accessToken").asText();

        sourceAccountId = createAccount("Conta Origem", "1000.00");
        targetAccountId = createAccount("Conta Destino", "0.00");
    }

    @Test
    void transfer_withSufficientBalance_shouldSucceed() throws Exception {
        var request = new TransferRequest(sourceAccountId, targetAccountId,
                new BigDecimal("100.00"), "Test transfer");

        mockMvc.perform(post("/api/v1/transactions/transfer")
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.amount").value("100.0"));
    }

    @Test
    void transfer_withInsufficientBalance_shouldReturn422() throws Exception {
        var request = new TransferRequest(sourceAccountId, targetAccountId,
                new BigDecimal("9999.00"), "Too big");

        mockMvc.perform(post("/api/v1/transactions/transfer")
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("Insufficient balance to complete the transfer"));
    }

    @Test
    void transfer_withSameIdempotencyKey_shouldReturnSameResult() throws Exception {
        var request = new TransferRequest(sourceAccountId, targetAccountId,
                new BigDecimal("50.00"), "Idempotent");
        var idempotencyKey = UUID.randomUUID().toString();

        var firstResult = mockMvc.perform(post("/api/v1/transactions/transfer")
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        var firstId = objectMapper.readTree(firstResult.getResponse().getContentAsString())
                .get("id").asText();

        var secondResult = mockMvc.perform(post("/api/v1/transactions/transfer")
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        var secondId = objectMapper.readTree(secondResult.getResponse().getContentAsString())
                .get("id").asText();

        assert firstId.equals(secondId) : "Idempotent requests must return the same transaction ID";
    }

    @Test
    void getTransactions_withPagination_shouldReturnPage() throws Exception {
        mockMvc.perform(get("/api/v1/transactions")
                        .header("Authorization", "Bearer " + accessToken)
                        .param("accountId", sourceAccountId.toString())
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    private UUID createAccount(String name, String initialBalance) throws Exception {
        var createRequest = new CreateAccountRequest(name, "BRL");
        var result = mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andReturn();

        var body = objectMapper.readTree(result.getResponse().getContentAsString());
        return UUID.fromString(body.get("id").asText());
    }
}
