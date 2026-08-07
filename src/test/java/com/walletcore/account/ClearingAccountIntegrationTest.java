package com.walletcore.account;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletcore.AbstractIntegrationTest;
import com.walletcore.account.repository.AccountRepository;
import com.walletcore.auth.dto.LoginRequest;
import com.walletcore.auth.dto.RegisterRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

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
}
