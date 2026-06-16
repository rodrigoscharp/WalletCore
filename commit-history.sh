#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

echo "==> Creating WalletCore commit history..."

# ── Commit 1: scaffold ──────────────────────────────────────────────────────
git add \
  pom.xml \
  docker-compose.yml \
  src/main/resources/application.yml \
  src/main/java/com/walletcore/WalletCoreApplication.java

git commit -m "chore: add project scaffold, Maven build, Docker Compose and application config

- pom.xml with all dependencies (Spring Boot 3.3, JWT, JPA, RabbitMQ, Redis,
  Bucket4j, Flyway, Spring Batch, Testcontainers, SpringDoc OpenAPI 2)
- docker-compose.yml with PostgreSQL 16, RabbitMQ 3.13 and Redis 7.4
- application.yml with full config (virtual threads, JWT, RabbitMQ, Redis,
  Flyway, Spring Batch, rate-limit properties)
- WalletCoreApplication bootstraps @EnableCaching, @EnableRetry, @EnableScheduling

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"

echo "  ✓ Commit 1: scaffold"

# ── Commit 2: Flyway migrations ─────────────────────────────────────────────
git add src/main/resources/db/migration/

git commit -m "feat: add Flyway migrations V1-V7 for full schema

V1 - users: email unique constraint + index
V2 - refresh_tokens: FK to users, token unique index
V3 - accounts: FK to users, CHECK balance >= 0, status enum
V4 - transactions: idempotency_key unique, CHECK amount > 0, indexes on
     source/target account and created_at DESC
V5 - ledger_entries: double-entry model (DEBIT/CREDIT), FK to transaction+account
V6 - notifications: status tracking with attempts counter
V7 - Spring Batch metadata tables and sequences

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"

echo "  ✓ Commit 2: migrations"

# ── Commit 3: JPA entities ───────────────────────────────────────────────────
git add \
  src/main/java/com/walletcore/user/entity/ \
  src/main/java/com/walletcore/auth/entity/ \
  src/main/java/com/walletcore/account/entity/ \
  src/main/java/com/walletcore/transaction/entity/ \
  src/main/java/com/walletcore/ledger/entity/ \
  src/main/java/com/walletcore/notification/entity/

git commit -m "feat: add JPA entities for all domains

- User implements UserDetails; Role enum (USER/ADMIN)
- RefreshToken: revoke() helper, isExpired() check
- Account: credit()/debit() enforce non-negative balance invariant;
  AccountStatus enum (ACTIVE/BLOCKED/CLOSED)
- Transaction: static factory Transaction.create(); immutable after creation;
  complete()/fail()/reverse() status transitions
- LedgerEntry: static factory LedgerEntry.of(); fully immutable (no setters)
- Notification: markSent()/markFailed()/incrementAttempts() lifecycle helpers

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"

echo "  ✓ Commit 3: entities"

# ── Commit 4: infrastructure config ─────────────────────────────────────────
git add \
  src/main/java/com/walletcore/config/SecurityConfig.java \
  src/main/java/com/walletcore/config/RabbitMQConfig.java \
  src/main/java/com/walletcore/config/RedisConfig.java \
  src/main/java/com/walletcore/config/OpenApiConfig.java \
  src/main/java/com/walletcore/config/error/

git commit -m "feat: add infrastructure configuration and global error handling

- SecurityConfig: stateless JWT, CORS, public auth/swagger routes
- RabbitMQConfig: DirectExchange, notification queue with DLQ arguments,
  Jackson2JsonMessageConverter
- RedisConfig: GenericJackson2JsonRedisSerializer with JavaTimeModule,
  5-minute default TTL cache manager
- OpenApiConfig: Swagger UI with bearer auth scheme
- GlobalExceptionHandler: standardised ErrorResponse (timestamp/status/error/
  message/path) for ApiException, @Valid failures, BadCredentials, 500s
- Http403EntryPoint: JSON 401 for unauthenticated requests
- ApiException / ErrorResponse records

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"

echo "  ✓ Commit 4: config"

# ── Commit 5: auth layer ─────────────────────────────────────────────────────
git add \
  src/main/java/com/walletcore/auth/ \
  src/main/java/com/walletcore/user/repository/

git commit -m "feat: add complete authentication layer (JWT + refresh tokens)

- JwtProperties: config-bound secret, access/refresh expiration
- JwtService: JJWT 0.12 builder/parser; generateAccessToken, isTokenValid,
  extractUsername
- JwtAuthenticationFilter: OncePerRequestFilter; silently skips on parse error
- UserDetailsServiceImpl: loads User entity by email
- UserRepository: findByEmail, existsByEmail
- RefreshTokenRepository: findByToken, revokeAllByUser (JPQL bulk update)
- AuthService: register (email uniqueness), login (revoke old tokens, issue
  new pair), refresh (validate + rotate), logout (revoke single token)
- AuthController: POST /api/v1/auth/{register,login,refresh,logout}
- DTOs: RegisterRequest, LoginRequest, RefreshRequest, AuthResponse (records)

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"

echo "  ✓ Commit 5: auth"

# ── Commit 6: Account domain ─────────────────────────────────────────────────
git add src/main/java/com/walletcore/account/

git commit -m "feat: add Account domain (repository, service, controller, DTOs)

- AccountRepository: findAllByUser, findByIdWithLock (@Lock PESSIMISTIC_WRITE)
- AccountService: createAccount (@CacheEvict), listAccounts (@Cacheable),
  getBalance, findAccountOwnedBy (ownership + status guard)
- AccountController: POST /api/v1/accounts, GET /api/v1/accounts,
  GET /api/v1/accounts/{id}/balance
- DTOs: CreateAccountRequest, AccountResponse, BalanceResponse (records)

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"

echo "  ✓ Commit 6: account"

# ── Commit 7: Transaction + Ledger ───────────────────────────────────────────
git add \
  src/main/java/com/walletcore/transaction/ \
  src/main/java/com/walletcore/ledger/

git commit -m "feat: add Transaction domain with double-entry ledger and idempotency

- TransactionRepository: findByIdempotencyKey for idempotency check;
  findByAccountIdAndFilters (JPQL with optional date/status/type params, paged)
- LedgerEntryRepository: findAllByTransactionId
- LedgerService: recordTransfer saves exactly two LedgerEntry per transaction
  (DEBIT on source, CREDIT on target) capturing balance_after snapshot
- TransactionService:
  - Idempotency: returns existing transaction if key already exists
  - Deterministic lock ordering (lower UUID first) to prevent deadlock
  - Pessimistic write lock via accountRepository.findByIdWithLock
  - Validates ownership, sufficient balance, distinct accounts
  - Calls ledgerService.recordTransfer then publishes notification event
  - listTransactions / getById with ownership guard
- TransactionController: POST /api/v1/transactions/transfer (requires
  Idempotency-Key header), GET /api/v1/transactions, GET /api/v1/transactions/{id}
- DTOs: TransferRequest, TransactionResponse (records)

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"

echo "  ✓ Commit 7: transaction + ledger"

# ── Commit 8: Notifications ───────────────────────────────────────────────────
git add src/main/java/com/walletcore/notification/

git commit -m "feat: add async notification system via RabbitMQ with retry and DLQ

- TransferNotificationEvent: lightweight record published after each transfer
- NotificationProducer: publishes to walletcore.exchange with routing key
  notification.created; catches publish errors to avoid polluting transfer tx
- NotificationConsumer: @RabbitListener with manual ack; @Retryable
  (3 attempts, exponential backoff 1s→2s→4s); @Recover sends to DLQ via
  basicNack and persists failed notification record
- NotificationService: processTransferNotification saves SENT record;
  saveFailedNotification saves FAILED record for DLQ messages
- NotificationRepository: JpaRepository<Notification, UUID>

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"

echo "  ✓ Commit 8: notifications"

# ── Commit 9: Spring Batch ────────────────────────────────────────────────────
git add src/main/java/com/walletcore/batch/

git commit -m "feat: add Spring Batch jobs for monthly statement and daily balance report

- BatchConfig: monthlyStatementJob (reads completed transactions by account in
  date range, writes summary log); dailyBalanceReportJob (reads all active
  accounts, logs balance snapshot); both use chunk-oriented processing
- BatchScheduler: @Scheduled cron triggers — statement on 1st of each month
  at 02:00, balance report daily at 01:00; launches jobs via JobLauncher with
  unique JobParameters per run

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"

echo "  ✓ Commit 9: batch"

# ── Commit 10: Rate limiting ───────────────────────────────────────────────────
git add src/main/java/com/walletcore/ratelimit/

git commit -m "feat: add per-user rate limiting with Bucket4j and Redis

- RateLimitProperties: binds walletcore.rate-limit.transfer.* (capacity,
  refill-tokens, refill-duration-minutes)
- RateLimitService: creates/fetches Bucket keyed by username from Redis;
  tryConsume throws 429 ApiException when bucket is exhausted
- TransactionController already wired: rateLimitService.checkTransferLimit
  called before transfer processing

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"

echo "  ✓ Commit 10: rate limiting"

# ── Commit 11: Integration test base ─────────────────────────────────────────
git add src/test/

git commit -m "test: add AbstractIntegrationTest base with Testcontainers

- Spins up PostgreSQL 16, RabbitMQ 3.13 and Redis 7.4 via Testcontainers
- @DynamicPropertySource wires container ports into Spring context
- Reuses containers across test classes via @Testcontainers + static fields
- Subclasses get full application context with real infrastructure

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"

echo "  ✓ Commit 11: integration test base"

echo ""
echo "All 11 commits created. Run 'git log --oneline' to verify, then 'git push'."
