package com.walletcore.transaction.controller;

import com.walletcore.ratelimit.config.RateLimitService;
import com.walletcore.transaction.dto.TransactionResponse;
import com.walletcore.transaction.dto.TransferRequest;
import com.walletcore.transaction.entity.Transaction;
import com.walletcore.transaction.service.TransactionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/transactions")
@Tag(name = "Transactions")
@SecurityRequirement(name = "bearerAuth")
public class TransactionController {

    private final TransactionService transactionService;
    private final RateLimitService rateLimitService;

    public TransactionController(TransactionService transactionService,
                                  RateLimitService rateLimitService) {
        this.transactionService = transactionService;
        this.rateLimitService = rateLimitService;
    }

    @PostMapping("/transfer")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Transfer between accounts")
    public TransactionResponse transfer(
            @Valid @RequestBody TransferRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal UserDetails userDetails) {

        rateLimitService.checkTransferLimit(userDetails.getUsername());
        return transactionService.transfer(request, idempotencyKey);
    }

    @GetMapping
    @Operation(summary = "List transactions with filters and pagination")
    public Page<TransactionResponse> list(
            @RequestParam UUID accountId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant endDate,
            @RequestParam(required = false) Transaction.TransactionStatus status,
            @RequestParam(required = false) Transaction.TransactionType type,
            @PageableDefault(size = 20) Pageable pageable) {

        return transactionService.listTransactions(accountId, startDate, endDate, status, type, pageable);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get transaction by ID")
    public TransactionResponse getById(@PathVariable UUID id) {
        return transactionService.getById(id);
    }
}
