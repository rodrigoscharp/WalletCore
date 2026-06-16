package com.walletcore.transaction.entity;

import com.walletcore.account.entity.Account;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "transactions")
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_account_id", nullable = false)
    private Account sourceAccount;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "target_account_id", nullable = false)
    private Account targetAccount;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency = "BRL";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionStatus status = TransactionStatus.PENDING;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @Column(length = 255)
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Transaction() {}

    public static Transaction create(Account source, Account target, BigDecimal amount,
                                     String currency, TransactionType type,
                                     String idempotencyKey, String description) {
        var tx = new Transaction();
        tx.sourceAccount = source;
        tx.targetAccount = target;
        tx.amount = amount;
        tx.currency = currency;
        tx.type = type;
        tx.idempotencyKey = idempotencyKey;
        tx.description = description;
        return tx;
    }

    public void complete() { this.status = TransactionStatus.COMPLETED; }
    public void fail() { this.status = TransactionStatus.FAILED; }
    public void reverse() { this.status = TransactionStatus.REVERSED; }

    public UUID getId() { return id; }
    public Account getSourceAccount() { return sourceAccount; }
    public Account getTargetAccount() { return targetAccount; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public TransactionType getType() { return type; }
    public TransactionStatus getStatus() { return status; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getDescription() { return description; }
    public Instant getCreatedAt() { return createdAt; }

    public enum TransactionType { TRANSFER, DEPOSIT, WITHDRAWAL }
    public enum TransactionStatus { PENDING, COMPLETED, FAILED, REVERSED }
}
