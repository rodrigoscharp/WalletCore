package com.walletcore.transaction.repository;

import com.walletcore.transaction.entity.Transaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);

    @Query("""
            SELECT t FROM Transaction t
            WHERE (t.sourceAccount.id = :accountId OR t.targetAccount.id = :accountId)
              AND (:startDate IS NULL OR t.createdAt >= :startDate)
              AND (:endDate IS NULL OR t.createdAt <= :endDate)
              AND (:status IS NULL OR t.status = :status)
              AND (:type IS NULL OR t.type = :type)
            ORDER BY t.createdAt DESC
            """)
    Page<Transaction> findByAccountIdAndFilters(
            UUID accountId,
            Instant startDate,
            Instant endDate,
            Transaction.TransactionStatus status,
            Transaction.TransactionType type,
            Pageable pageable);
}
