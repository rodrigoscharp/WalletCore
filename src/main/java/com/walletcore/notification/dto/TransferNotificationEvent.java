package com.walletcore.notification.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransferNotificationEvent(
        UUID transactionId,
        UUID userId,
        UUID sourceAccountId,
        UUID targetAccountId,
        BigDecimal amount,
        String currency,
        Instant occurredAt
) {}
