package com.walletcore;

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
 * <p>Containers seguem o padrão <em>singleton</em>: campos {@code static final} iniciados uma única
 * vez num bloco {@code static} que chama {@code .start()}. Não usamos {@code @Testcontainers} /
 * {@code @Container} de propósito — a extensão JUnit para os containers no {@code afterAll} de cada
 * classe, mas o {@code ApplicationContext} do Spring é reaproveitado entre classes, então a segunda
 * classe de teste falaria com um container já morto. Ninguém para os containers: o ryuk do
 * Testcontainers os remove quando a JVM termina.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES;
    static final RabbitMQContainer RABBITMQ;
    static final GenericContainer<?> REDIS;

    static {
        POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("walletcore_test")
                .withUsername("walletcore")
                .withPassword("walletcore_secret");

        RABBITMQ = new RabbitMQContainer("rabbitmq:3.13-management-alpine")
                .withUser("walletcore", "walletcore_secret");

        REDIS = new GenericContainer<>("redis:7.4-alpine")
                .withExposedPorts(6379);

        POSTGRES.start();
        RABBITMQ.start();
        REDIS.start();
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
