package com.walletcore.ledger.entity;

import com.walletcore.account.entity.Account;
import com.walletcore.transaction.entity.Transaction;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ledger_entries")
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transaction_id", nullable = false)
    private Transaction transaction;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 6)
    private EntryType entryType;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "balance_after", nullable = false, precision = 19, scale = 4)
    private BigDecimal balanceAfter;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected LedgerEntry() {}

    public static LedgerEntry of(Transaction transaction, Account account,
                                 EntryType type, BigDecimal amount, BigDecimal balanceAfter) {
        var entry = new LedgerEntry();
        entry.transaction = transaction;
        entry.account = account;
        entry.entryType = type;
        entry.amount = amount;
        entry.balanceAfter = balanceAfter;
        return entry;
    }

    public UUID getId() { return id; }
    public Transaction getTransaction() { return transaction; }
    public Account getAccount() { return account; }
    public EntryType getEntryType() { return entryType; }
    public BigDecimal getAmount() { return amount; }
    public BigDecimal getBalanceAfter() { return balanceAfter; }
    public Instant getCreatedAt() { return createdAt; }

    public enum EntryType { DEBIT, CREDIT }
}
