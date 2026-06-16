package com.walletcore.account;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletcore.AbstractIntegrationTest;
import com.walletcore.account.dto.CreateAccountRequest;
import com.walletcore.auth.dto.LoginRequest;
import com.walletcore.auth.dto.RegisterRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AccountIntegrationTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    String accessToken;

    @BeforeEach
    void setUp() throws Exception {
        var email = "account+" + UUID.randomUUID() + "@walletcore.com";
        var password = "Senha@1234";

        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        new RegisterRequest(email, password, "Account User"))));

        var loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, password))))
                .andReturn();

        var loginBody = objectMapper.readTree(loginResult.getResponse().getContentAsString());
        accessToken = loginBody.get("accessToken").asText();
    }

    @Test
    void createAccount_shouldReturn201WithAccountData() throws Exception {
        var request = new CreateAccountRequest("Conta Principal", "BRL");

        mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.name").value("Conta Principal"))
                .andExpect(jsonPath("$.currency").value("BRL"))
                .andExpect(jsonPath("$.balance").value(0));
    }

    @Test
    void listAccounts_shouldReturnOwnedAccounts() throws Exception {
        mockMvc.perform(post("/api/v1/accounts")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CreateAccountRequest("Conta A", "BRL"))));

        mockMvc.perform(get("/api/v1/accounts")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void getBalance_shouldReturnCurrentBalance() throws Exception {
        var createResult = mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateAccountRequest("Conta B", "BRL"))))
                .andReturn();

        var accountId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .get("id").asText();

        mockMvc.perform(get("/api/v1/accounts/" + accountId + "/balance")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(0))
                .andExpect(jsonPath("$.currency").value("BRL"));
    }

    @Test
    void createAccount_withoutAuth_shouldReturn401() throws Exception {
        var request = new CreateAccountRequest("Conta Sem Auth", "BRL");
        mockMvc.perform(post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }
}
