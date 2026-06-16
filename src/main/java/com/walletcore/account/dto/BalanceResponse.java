package com.walletcore.account.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record BalanceResponse(
        UUID accountId,
        BigDecimal balance,
        String currency,
        Instant asOf
) {}
