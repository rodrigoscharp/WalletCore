package com.walletcore;

import org.junit.jupiter.api.BeforeAll;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;

/**
 * Base de todos os testes de integração.
 *
 * <p>Containers seguem o padrão <em>singleton</em>: campos {@code static final} construídos na
 * declaração e iniciados uma única vez num bloco {@code static}. Não usamos {@code @Testcontainers}
 * / {@code @Container} de propósito — a extensão JUnit para os containers no {@code afterAll} de
 * cada classe, mas o {@code ApplicationContext} do Spring é reaproveitado entre classes, então a
 * segunda classe de teste falaria com um container já morto. Ninguém para os containers: o ryuk do
 * Testcontainers os remove quando a JVM termina.
 *
 * <p><strong>Tratamento de falha na subida:</strong> o bloco {@code static} não deixa a exceção
 * escapar. Inicialização estática roda uma única vez por classloader — se ela falhasse, apenas a
 * primeira classe de teste veria a causa raiz e todas as seguintes receberiam um
 * {@code NoClassDefFoundError: Could not initialize class AbstractIntegrationTest} pelado, sem
 * causa anexada. Em vez disso a falha é guardada em {@link #STARTUP_FAILURE} e relançada em
 * {@code @BeforeAll}, com a causa original encadeada, para <em>cada</em> classe de teste.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(AbstractIntegrationTest.class);

    // Os construtores não falam com o Docker — só montam o objeto. O contato com o daemon
    // acontece em .start(), abaixo, que é o que pode falhar.
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("walletcore_test")
            .withUsername("walletcore")
            .withPassword("walletcore_secret");

    static final RabbitMQContainer RABBITMQ = new RabbitMQContainer("rabbitmq:3.13-management-alpine")
            .withUser("walletcore", "walletcore_secret");

    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine")
            .withExposedPorts(6379);

    /** Falha da subida dos containers, ou {@code null} se todos subiram. */
    private static final Throwable STARTUP_FAILURE;

    static {
        Throwable failure = null;
        try {
            POSTGRES.start();
            RABBITMQ.start();
            REDIS.start();
        } catch (Throwable t) {
            failure = t;
            log.error("""
                    Falha ao subir os containers de teste (PostgreSQL / RabbitMQ / Redis).
                    A suíte de integração se autoprovisiona via Testcontainers e precisa de um \
                    daemon Docker acessível — verifique com 'docker info'.
                    Nenhum container precisa ser criado à mão.""", t);
        }
        STARTUP_FAILURE = failure;
    }

    /**
     * Roda antes de cada classe de teste (herdado). Garante que a causa raiz de uma falha de
     * infraestrutura apareça na primeira mensagem lida, em toda classe, e não só na primeira.
     */
    @BeforeAll
    static void requireContainers() {
        if (STARTUP_FAILURE != null) {
            throw new IllegalStateException(
                    "Containers de teste indisponíveis: não foi possível subir PostgreSQL/RabbitMQ/Redis "
                            + "via Testcontainers. A suíte se autoprovisiona e exige um daemon Docker "
                            + "acessível (confira com 'docker info'). Causa raiz encadeada abaixo.",
                    STARTUP_FAILURE);
        }
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        registry.add("spring.rabbitmq.host", RABBITMQ::getHost);
        registry.add("spring.rabbitmq.port", RABBITMQ::getAmqpPort);
        registry.add("spring.rabbitmq.username", () -> "walletcore");
        registry.add("spring.rabbitmq.password", () -> "walletcore_secret");

        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");

        // CacheManager em memória nos testes; o bean RedisCacheManager de RedisConfig é
        // condicionado a spring.cache.type=redis, então aqui vale o ConcurrentMapCacheManager
        // da auto-configuração do Spring Boot.
        registry.add("spring.cache.type", () -> "simple");
    }
}
