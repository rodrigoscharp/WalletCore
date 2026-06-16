package com.walletcore.transaction.service;

import com.walletcore.account.repository.AccountRepository;
import com.walletcore.account.service.AccountService;
import com.walletcore.config.error.ApiException;
import com.walletcore.ledger.service.LedgerService;
import com.walletcore.notification.producer.NotificationProducer;
import com.walletcore.transaction.dto.TransactionResponse;
import com.walletcore.transaction.dto.TransferRequest;
import com.walletcore.transaction.entity.Transaction;
import com.walletcore.transaction.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class TransactionService {

    private static final Logger log = LoggerFactory.getLogger(TransactionService.class);

    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;
    private final AccountService accountService;
    private final LedgerService ledgerService;
    private final NotificationProducer notificationProducer;

    public TransactionService(TransactionRepository transactionRepository,
                               AccountRepository accountRepository,
                               AccountService accountService,
                               LedgerService ledgerService,
                               NotificationProducer notificationProducer) {
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
        this.accountService = accountService;
        this.ledgerService = ledgerService;
        this.notificationProducer = notificationProducer;
    }

    @Transactional
    public TransactionResponse transfer(TransferRequest request, String idempotencyKey) {
        // Idempotência: retorna transação existente se a chave já foi processada
        var existing = transactionRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            log.info("Idempotent transfer request, returning existing tx: {}", idempotencyKey);
            return TransactionResponse.from(existing.get());
        }

        var user = accountService.currentUser();

        // Lock pessimista em ordem determinística para evitar deadlock
        var sourceId = request.sourceAccountId();
        var targetId = request.targetAccountId();

        if (sourceId.equals(targetId)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Source and target accounts must be different");
        }

        // Garante ordem de lock consistente (menor UUID primeiro)
        var firstLockId  = sourceId.compareTo(targetId) < 0 ? sourceId : targetId;
        var secondLockId = sourceId.compareTo(targetId) < 0 ? targetId : sourceId;

        var firstAccount  = accountRepository.findByIdWithLock(firstLockId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Account not found"));
        var secondAccount = accountRepository.findByIdWithLock(secondLockId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Account not found"));

        var source = firstLockId.equals(sourceId) ? firstAccount : secondAccount;
        var target = firstLockId.equals(targetId) ? firstAccount : secondAccount;

        // Valida posse da conta origem
        accountService.findAccountOwnedBy(source.getId(), user);

        if (source.getBalance().compareTo(request.amount()) < 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Insufficient balance to complete the transfer");
        }

        var transaction = Transaction.create(
                source, target, request.amount(), source.getCurrency(),
                Transaction.TransactionType.TRANSFER, idempotencyKey, request.description());
        transactionRepository.save(transaction);

        source.debit(request.amount());
        target.credit(request.amount());

        ledgerService.recordTransfer(transaction, source, target, request.amount());
        transaction.complete();

        log.info("Transfer completed: {} -> {} amount={} tx={}",
                source.getId(), target.getId(), request.amount(), transaction.getId());

        notificationProducer.publishTransferEvent(transaction, user);

        return TransactionResponse.from(transaction);
    }

    @Transactional(readOnly = true)
    public Page<TransactionResponse> listTransactions(UUID accountId,
                                                       Instant startDate, Instant endDate,
                                                       Transaction.TransactionStatus status,
                                                       Transaction.TransactionType type,
                                                       Pageable pageable) {
        var user = accountService.currentUser();
        accountService.findAccountOwnedBy(accountId, user);

        return transactionRepository
                .findByAccountIdAndFilters(accountId, startDate, endDate, status, type, pageable)
                .map(TransactionResponse::from);
    }

    @Transactional(readOnly = true)
    public TransactionResponse getById(UUID id) {
        var tx = transactionRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Transaction not found"));

        var user = accountService.currentUser();
        var ownsSource = tx.getSourceAccount().getUser().getId().equals(user.getId());
        var ownsTarget = tx.getTargetAccount().getUser().getId().equals(user.getId());

        if (!ownsSource && !ownsTarget) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Access denied to this transaction");
        }

        return TransactionResponse.from(tx);
    }
}
