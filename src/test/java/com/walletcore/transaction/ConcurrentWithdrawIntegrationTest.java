package com.walletcore.transaction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletcore.AbstractIntegrationTest;
import com.walletcore.account.dto.CreateAccountRequest;
import com.walletcore.auth.dto.LoginRequest;
import com.walletcore.auth.dto.RegisterRequest;
import com.walletcore.config.error.ApiException;
import com.walletcore.transaction.dto.AmountRequest;
import com.walletcore.transaction.service.TransactionService;
import com.walletcore.user.entity.User;
import com.walletcore.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cobertura de concorrência do caminho do dinheiro — o lock pessimista é a promessa central do
 * projeto e até aqui nenhum teste a exercitava.
 *
 * <p><strong>O que este teste prova:</strong> dois saques simultâneos do saldo inteiro da mesma
 * conta produzem exatamente um sucesso. O assert decisivo não é o saldo final: no cenário quebrado
 * ele também termina em zero (as duas transações leem 100, as duas gravam 100-100=0). O que separa
 * o certo do errado é a reconciliação do razão — soma de créditos menos débitos da conta tem que
 * bater com o saldo. Com dois saques aceitos indevidamente, o razão fecha em -100 contra um saldo
 * de 0, ou seja, 100 criados do nada — exatamente o sintoma do lost update em que o
 * {@code SELECT ... FOR UPDATE} trava a linha mas o Hibernate devolve a instância que já estava no
 * contexto de persistência, carregada antes do lock.
 *
 * <p><strong>Por que não usa MockMvc:</strong> ele não é feito para ser dirigido de várias
 * threads. As duas threads chamam {@link TransactionService} pelo proxy do Spring — não por
 * auto-invocação — para que cada uma abra a própria transação, e cada uma popula o próprio
 * {@code SecurityContext} (a estratégia padrão do {@code SecurityContextHolder} é por thread e o
 * serviço lê o principal de lá).
 *
 * <p><strong>Por que não pisca:</strong> as asserções não dependem de quem chegou primeiro nem de
 * as duas threads realmente se encavalarem. Um {@link CountDownLatch} solta as duas juntas para
 * maximizar a sobreposição, mas mesmo se uma terminasse antes de a outra começar o resultado
 * esperado seria o mesmo — um sucesso, uma recusa por saldo, razão fechando. Não há espera por
 * tempo em lugar nenhum: a thread perdedora fica bloqueada no lock de linha do Postgres (que não
 * tem {@code lock_timeout} configurado) até a vencedora commitar, e a ordem de lock por UUID
 * elimina deadlock. O saldo e o razão são lidos por {@link JdbcTemplate}, direto do banco, sem
 * passar por nenhum cache de JPA.
 */
class ConcurrentWithdrawIntegrationTest extends AbstractIntegrationTest {

    private static final BigDecimal BALANCE = new BigDecimal("100.00");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TransactionService transactionService;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    User user;
    UUID accountId;

    @BeforeEach
    void setUp() throws Exception {
        var email = "concurrent+" + UUID.randomUUID() + "@walletcore.com";
        var password = "Senha@1234";

        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        new RegisterRequest(email, password, "Concurrent User"))));

        var loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, password))))
                .andReturn();
        var token = objectMapper.readTree(loginResult.getResponse().getContentAsString())
                .get("accessToken").asText();

        var createResult = mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateAccountRequest("Conta Concorrente", "BRL"))))
                .andReturn();
        accountId = UUID.fromString(objectMapper
                .readTree(createResult.getResponse().getContentAsString()).get("id").asText());

        mockMvc.perform(post("/api/v1/accounts/" + accountId + "/deposit")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new AmountRequest(BALANCE, "Saldo inicial"))))
                .andExpect(status().isCreated());

        // O principal que o AccountService.currentUser() espera é a própria entidade User.
        user = userRepository.findByEmail(email).orElseThrow();
    }

    @Test
    void twoConcurrentWithdrawalsOfFullBalance_shouldSettleExactlyOne() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var atTheGate = new CountDownLatch(2);
        var go = new CountDownLatch(1);

        Callable<Optional<Throwable>> withdrawFullBalance = () -> {
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
            try {
                atTheGate.countDown();
                go.await();
                transactionService.withdraw(accountId,
                        new AmountRequest(BALANCE, "Saque concorrente"),
                        UUID.randomUUID().toString());
                return Optional.empty();
            } catch (Throwable t) {
                return Optional.of(t);
            } finally {
                SecurityContextHolder.clearContext();
            }
        };

        List<Future<Optional<Throwable>>> futures;
        try {
            futures = List.of(executor.submit(withdrawFullBalance),
                    executor.submit(withdrawFullBalance));

            assertTrue(atTheGate.await(30, TimeUnit.SECONDS), "As duas threads devem chegar ao portão");
            go.countDown();

            var outcomes = new ArrayList<Optional<Throwable>>();
            for (var future : futures) {
                outcomes.add(future.get(60, TimeUnit.SECONDS));
            }

            var succeeded = outcomes.stream().filter(Optional::isEmpty).count();
            var failed = outcomes.stream().filter(Optional::isPresent).toList();

            assertEquals(1, succeeded,
                    "Exatamente um saque do saldo inteiro pode ser aceito; o outro tem que ver o "
                            + "saldo já debitado sob o lock. Falhas observadas: " + failed);
            assertEquals(1, failed.size());

            var rejection = failed.getFirst().orElseThrow();
            var apiException = assertInstanceOf(ApiException.class, rejection,
                    "O saque perdedor deve ser recusado por regra de negócio, não estourar: " + rejection);
            assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, apiException.getStatus());
            assertEquals("Insufficient balance to complete the transfer", apiException.getMessage());
        } finally {
            executor.shutdownNow();
        }

        var finalBalance = jdbcTemplate.queryForObject(
                "SELECT balance FROM accounts WHERE id = ?", BigDecimal.class, accountId);
        assertEquals(0, BigDecimal.ZERO.compareTo(finalBalance),
                "Depósito de 100 e um único saque de 100 devem zerar a conta, saldo real=" + finalBalance);

        // A asserção que de fato pega o lost update: com os dois saques aceitos o razão fecha em
        // -100 contra um saldo de 0.
        var ledgerBalance = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN entry_type = 'CREDIT' THEN amount ELSE -amount END), 0)
                FROM ledger_entries
                WHERE account_id = ?
                """, BigDecimal.class, accountId);
        assertEquals(0, ledgerBalance.compareTo(finalBalance),
                "Razão (" + ledgerBalance + ") tem que reconciliar com o saldo (" + finalBalance + ")");

        var settledWithdrawals = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM transactions
                WHERE source_account_id = ? AND type = 'WITHDRAWAL' AND status = 'COMPLETED'
                """, Integer.class, accountId);
        assertEquals(1, settledWithdrawals, "Só um saque pode ter sido liquidado");
    }
}
