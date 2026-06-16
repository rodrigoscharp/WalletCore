<div align="center">

# 💳 WalletCore

**Sistema de carteira digital de nível profissional**  
construído com Java 21, Spring Boot 3 e arquitetura orientada a domínio

[![Java](https://img.shields.io/badge/Java-21-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.3-6DB33F?style=for-the-badge&logo=spring-boot&logoColor=white)](https://spring.io/projects/spring-boot)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-316192?style=for-the-badge&logo=postgresql&logoColor=white)](https://www.postgresql.org/)
[![RabbitMQ](https://img.shields.io/badge/RabbitMQ-3.13-FF6600?style=for-the-badge&logo=rabbitmq&logoColor=white)](https://www.rabbitmq.com/)
[![Redis](https://img.shields.io/badge/Redis-7.4-DC382D?style=for-the-badge&logo=redis&logoColor=white)](https://redis.io/)
[![Docker](https://img.shields.io/badge/Docker-Compose-2496ED?style=for-the-badge&logo=docker&logoColor=white)](https://docs.docker.com/compose/)
[![License](https://img.shields.io/badge/License-MIT-green?style=for-the-badge)](LICENSE)

</div>

---

## Visão Geral

WalletCore é uma **API REST de carteira digital** que implementa operações financeiras com foco em **segurança**, **consistência** e **resiliência**. Desenvolvida como projeto de portfólio com código de nível profissional, cobre desde autenticação JWT até transferências atômicas com modelo de dupla entrada contábil.

### Por que este projeto é relevante?

- **Segurança real** — JWT com refresh token rotativo, BCrypt, rate limiting por usuário
- **Consistência financeira** — transferências ACID com lock pessimista e ledger de dupla entrada
- **Resiliência** — retry exponencial, dead-letter queue, idempotência via chave única
- **Observabilidade** — logs estruturados com SLF4J, Spring Actuator, Swagger UI
- **Testes robustos** — Testcontainers com PostgreSQL e RabbitMQ reais (sem mocks de banco)

---

## Stack Tecnológica

| Camada | Tecnologia |
|---|---|
| Linguagem | Java 21 (Virtual Threads habilitado) |
| Framework | Spring Boot 3.3+ |
| Segurança | Spring Security 6 + JWT (JJWT 0.12) + Refresh Token |
| Persistência | Spring Data JPA + PostgreSQL 16 + Flyway |
| Mensageria | Spring AMQP + RabbitMQ 3.13 |
| Retry | Spring Retry + backoff exponencial (1s → 2s → 4s) |
| Jobs | Spring Batch (extrato mensal + relatório de saldo) |
| Documentação | SpringDoc OpenAPI 2 (Swagger UI) |
| Rate Limiting | Bucket4j (10 transferências/minuto por usuário) |
| Cache | Redis 7.4 (Spring Cache) |
| Testes | JUnit 5 + Testcontainers + MockMvc |
| Build | Maven |
| Ambiente | Docker + Docker Compose |

---

## Arquitetura

```
com.walletcore
├── auth/               # Autenticação JWT, refresh token, logout
│   ├── controller/     # POST /api/v1/auth/*
│   ├── service/        # AuthService — registro, login, refresh, logout
│   ├── security/       # JwtFilter, JwtService, UserDetailsServiceImpl
│   └── dto/            # RegisterRequest, LoginRequest, AuthResponse (records)
│
├── account/            # Contas financeiras do usuário
│   ├── controller/     # GET/POST /api/v1/accounts
│   ├── service/        # AccountService — criação, listagem, saldo
│   ├── repository/     # findByIdWithLock (PESSIMISTIC_WRITE)
│   └── entity/         # Account com credit()/debit() — saldo nunca negativo
│
├── transaction/        # Transferências com idempotência
│   ├── controller/     # POST /api/v1/transactions/transfer
│   ├── service/        # Lock ordenado por UUID → sem deadlock
│   └── repository/     # Filtros por data, tipo, status (paginado)
│
├── ledger/             # Dupla entrada contábil
│   ├── service/        # 1 transferência = 2 LedgerEntry (DEBIT + CREDIT)
│   └── entity/         # Imutável após criação
│
├── notification/       # Notificações assíncronas
│   ├── producer/       # Publica evento no RabbitMQ após transferência
│   ├── consumer/       # @Retryable 3x com backoff exponencial
│   └── entity/         # Rastreia tentativas e status (SENT/FAILED)
│
├── batch/              # Jobs agendados
│   ├── config/         # dailyBalanceReportJob, monthlyStatementJob
│   └── jobs/           # BatchScheduler — cron 01:00 e 02:00 do dia 1
│
├── ratelimit/          # Rate limiting por usuário
│   └── config/         # Bucket4j — 10 transferências/minuto
│
└── config/             # SecurityConfig, RabbitMQConfig, RedisConfig, OpenApiConfig
```

---

## Fluxo de uma Transferência

```
Cliente
  │
  ├─ POST /api/v1/transactions/transfer
  │    Header: Idempotency-Key: <uuid>
  │    Body: { sourceAccountId, targetAccountId, amount }
  │
  ▼
JwtAuthenticationFilter         ← valida Bearer token
  │
RateLimitService                ← verifica bucket do usuário (Bucket4j)
  │
TransactionService
  ├─ Verifica idempotência      ← retorna tx existente se chave já usada
  ├─ Lock PESSIMISTIC_WRITE     ← menor UUID primeiro (evita deadlock)
  ├─ Valida saldo               ← 422 se insuficiente
  ├─ source.debit(amount)       ← CHECK balance >= 0 no banco
  ├─ target.credit(amount)
  ├─ Transaction.complete()
  └─ LedgerService              ← 2 entradas: DEBIT(source) + CREDIT(target)
       │
       └─ NotificationProducer  ← publica evento no RabbitMQ (fire-and-forget)
                                        │
                                 NotificationConsumer
                                   ├─ Tentativa 1 → falha → aguarda 1s
                                   ├─ Tentativa 2 → falha → aguarda 2s
                                   ├─ Tentativa 3 → falha → aguarda 4s
                                   └─ DLQ (walletcore.notification.dlq)
```

---

## Modelo de Dados

```sql
users
  id (UUID PK) | email (UNIQUE) | password (BCrypt) | full_name | role | enabled

refresh_tokens
  id (UUID PK) | user_id (FK) | token (UNIQUE) | expires_at | revoked

accounts
  id (UUID PK) | user_id (FK) | name | currency | balance (>= 0) | status

transactions
  id (UUID PK) | source_account_id | target_account_id | amount
  currency | type | status | idempotency_key (UNIQUE) | created_at

ledger_entries                        -- imutavel apos criacao
  id (UUID PK) | transaction_id | account_id
  entry_type (DEBIT|CREDIT) | amount | balance_after

notifications
  id (UUID PK) | user_id | transaction_id | type | status | attempts
```

---

## Como Executar

### Pré-requisitos

- Java 21+
- Maven 3.9+
- Docker + Docker Compose

### 1. Clone e suba a infraestrutura

```bash
git clone https://github.com/rodrigoscharp/WalletCore.git
cd WalletCore

docker compose up -d
```

Isso sobe:
- **PostgreSQL 16** em `localhost:5432`
- **RabbitMQ 3.13** em `localhost:5672` (management UI: `localhost:15672`)
- **Redis 7.4** em `localhost:6379`

### 2. Execute a aplicação

```bash
mvn spring-boot:run
```

A aplicação inicia em `http://localhost:8080`.  
O Flyway aplica todas as migrations automaticamente na primeira execução.

### 3. Acesse a documentação interativa

```
http://localhost:8080/swagger-ui.html
```

---

## Endpoints da API

### Autenticação

| Método | Endpoint | Autenticação | Descrição |
|---|---|---|---|
| `POST` | `/api/v1/auth/register` | Pública | Registra novo usuário |
| `POST` | `/api/v1/auth/login` | Pública | Autentica e retorna tokens |
| `POST` | `/api/v1/auth/refresh` | Pública | Renova access token |
| `POST` | `/api/v1/auth/logout` | Pública | Invalida refresh token |

### Contas

| Método | Endpoint | Autenticação | Descrição |
|---|---|---|---|
| `POST` | `/api/v1/accounts` | JWT | Cria conta financeira |
| `GET` | `/api/v1/accounts` | JWT | Lista contas do usuário |
| `GET` | `/api/v1/accounts/{id}/balance` | JWT | Consulta saldo em tempo real |

### Transações

| Método | Endpoint | Autenticação | Descrição |
|---|---|---|---|
| `POST` | `/api/v1/transactions/transfer` | JWT + Idempotency-Key | Transferência entre contas |
| `GET` | `/api/v1/transactions` | JWT | Histórico paginado com filtros |
| `GET` | `/api/v1/transactions/{id}` | JWT | Detalhe de transação |

**Parâmetros de filtro para `GET /api/v1/transactions`:**

| Parâmetro | Tipo | Obrigatório | Descrição |
|---|---|---|---|
| `accountId` | UUID | Sim | Conta a consultar |
| `page` | int | Não | Página (default: 0) |
| `size` | int | Não | Itens por página (default: 20) |
| `startDate` | ISO-8601 | Não | Data inicial |
| `endDate` | ISO-8601 | Não | Data final |
| `status` | enum | Não | `PENDING`, `COMPLETED`, `FAILED`, `REVERSED` |
| `type` | enum | Não | `TRANSFER`, `DEPOSIT`, `WITHDRAWAL` |

---

## Exemplos de Uso

### Registrar e autenticar

```bash
curl -X POST http://localhost:8080/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"joao@email.com","password":"Senha@1234","fullName":"João Silva"}'
```

```json
{
  "accessToken": "eyJhbGciOiJIUzM4NCJ9...",
  "refreshToken": "ce088c85-c4a5-4ce6-86ad-4b55e6ead4ef",
  "tokenType": "Bearer",
  "expiresIn": 900
}
```

### Criar conta e transferir

```bash
# Criar conta
curl -X POST http://localhost:8080/api/v1/accounts \
  -H "Authorization: Bearer {accessToken}" \
  -H "Content-Type: application/json" \
  -d '{"name":"Conta Principal","currency":"BRL"}'

# Transferir
curl -X POST http://localhost:8080/api/v1/transactions/transfer \
  -H "Authorization: Bearer {accessToken}" \
  -H "Idempotency-Key: $(uuidgen)" \
  -H "Content-Type: application/json" \
  -d '{
    "sourceAccountId": "4aa780cd-69df-4123-97b7-6af8718377e0",
    "targetAccountId": "0c5eaab5-2f00-445e-9ed5-795671d972cb",
    "amount": 150.00,
    "description": "Pagamento aluguel"
  }'
```

```json
{
  "id": "c0d8417f-239c-4476-921a-99245df39784",
  "sourceAccountId": "4aa780cd-...",
  "targetAccountId": "0c5eaab5-...",
  "amount": 150.0,
  "currency": "BRL",
  "type": "TRANSFER",
  "status": "COMPLETED",
  "idempotencyKey": "36347003-A424-4647-B0DF-6C02E892A377",
  "description": "Pagamento aluguel",
  "createdAt": "2026-06-16T09:39:28.956787Z"
}
```

### Modelo de erro padronizado

Todos os erros retornam o mesmo formato:

```json
{
  "timestamp": "2026-06-16T10:00:00Z",
  "status": 422,
  "error": "Unprocessable Entity",
  "message": "Saldo insuficiente para realizar a transferência",
  "path": "/api/v1/transactions/transfer"
}
```

| Status | Situação |
|---|---|
| `401` | Token ausente ou inválido |
| `403` | Operação em conta de outro usuário |
| `404` | Recurso não encontrado |
| `409` | E-mail já cadastrado |
| `422` | Saldo insuficiente, contas iguais, conta inativa |
| `429` | Rate limit excedido (10 transferências/min) |

---

## Testes

Os testes de integração usam **Testcontainers** — nenhum mock de banco ou fila. Cada execução sobe instâncias reais de PostgreSQL e RabbitMQ via Docker.

```bash
mvn test
```

| Teste | Cenários cobertos |
|---|---|
| `AuthIntegrationTest` | Registro, login, refresh de token, e-mail duplicado, credencial inválida |
| `AccountIntegrationTest` | Criar conta, listar, consultar saldo, acesso sem autenticação |
| `TransferIntegrationTest` | Transferência bem-sucedida, saldo insuficiente, idempotência, paginação |

---

## Decisões Técnicas

**Lock pessimista em vez de otimista**  
Transferências financeiras têm alta probabilidade de conflito quando dois usuários operam a mesma conta simultaneamente. O lock pessimista garante exclusividade sem rollbacks por conflito.

**Ordenação dos locks pelo UUID**  
Sem ordem determinística, duas transferências `A→B` e `B→A` podem causar deadlock. Adquirindo sempre o lock do menor UUID primeiro, eliminamos a possibilidade de deadlock por definição.

**`Idempotency-Key` no header**  
Segue o padrão de APIs financeiras (Stripe, Adyen). O cliente gera a chave antes de enviar — mesmo que a rede falhe após o processamento, reenviar a mesma requisição retorna o resultado original sem duplicar a operação.

**Testcontainers em vez de H2**  
H2 emula apenas parcialmente o PostgreSQL: constraints `CHECK`, tipos `UUID` nativos e comportamento de locks diferem. Testcontainers garante que os testes rodem contra o mesmo engine da produção.

**Fire-and-forget nas notificações**  
O `NotificationProducer` captura exceções de publish para não propagar falhas do RabbitMQ para a transação financeira. A resiliência é tratada no consumer (retry + DLQ), não no fluxo principal.

---

## Autor

**Rodrigo Scharp**  
[GitHub](https://github.com/rodrigoscharp) · [LinkedIn](https://linkedin.com/in/rodrigoscharp)

---

<div align="center">
  <sub>WalletCore — Java 21 · Spring Boot 3 · PostgreSQL · RabbitMQ · Redis</sub>
</div>
