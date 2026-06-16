package com.walletcore.transaction.dto;

import com.walletcore.transaction.entity.Transaction;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransactionResponse(
        UUID id,
        UUID sourceAccountId,
        UUID targetAccountId,
        BigDecimal amount,
        String currency,
        String type,
        String status,
        String idempotencyKey,
        String description,
        Instant createdAt
) {
    public static TransactionResponse from(Transaction tx) {
        return new TransactionResponse(
                tx.getId(),
                tx.getSourceAccount().getId(),
                tx.getTargetAccount().getId(),
                tx.getAmount(),
                tx.getCurrency(),
                tx.getType().name(),
                tx.getStatus().name(),
                tx.getIdempotencyKey(),
                tx.getDescription(),
                tx.getCreatedAt()
        );
    }
}
