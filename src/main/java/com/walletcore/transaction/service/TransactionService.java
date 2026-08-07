package com.walletcore.transaction.service;

import com.walletcore.account.entity.Account;
import com.walletcore.account.repository.AccountRepository;
import com.walletcore.account.service.AccountService;
import com.walletcore.config.error.ApiException;
import com.walletcore.ledger.service.LedgerService;
import com.walletcore.notification.producer.NotificationProducer;
import com.walletcore.transaction.dto.AmountRequest;
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

import java.math.BigDecimal;
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
        return executeTransfer(
                request.sourceAccountId(),
                request.targetAccountId(),
                request.sourceAccountId(),
                request.amount(),
                Transaction.TransactionType.TRANSFER,
                idempotencyKey,
                request.description());
    }

    @Transactional
    public TransactionResponse deposit(UUID accountId, AmountRequest request, String idempotencyKey) {
        var clearing = resolveClearingFor(accountId);
        return executeTransfer(clearing.getId(), accountId, accountId, request.amount(),
                Transaction.TransactionType.DEPOSIT, idempotencyKey, request.description());
    }

    @Transactional
    public TransactionResponse withdraw(UUID accountId, AmountRequest request, String idempotencyKey) {
        var clearing = resolveClearingFor(accountId);
        return executeTransfer(accountId, clearing.getId(), accountId, request.amount(),
                Transaction.TransactionType.WITHDRAWAL, idempotencyKey, request.description());
    }

    private Account resolveClearingFor(UUID accountId) {
        var account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Account not found"));
        return accountService.findClearingAccount(account.getCurrency());
    }

    private TransactionResponse executeTransfer(UUID sourceId, UUID targetId, UUID ownedAccountId,
                                                BigDecimal amount, Transaction.TransactionType type,
                                                String idempotencyKey, String description) {
        var user = accountService.currentUser();

        // Confirma posse antes de expor qualquer coisa: idempotency_key tem UNIQUE global na
        // tabela, então uma chave reusada pode colidir com uma transação de outro usuário. Sem
        // essa checagem aqui, o branch de replay abaixo devolveria o corpo de uma transação que
        // o chamador nunca provou ser dele.
        accountService.findAccountOwnedBy(ownedAccountId, user);

        // Idempotência: retorna transação existente se a chave já foi processada — mas só se
        // ela corresponder à mesma operação (mesmo tipo, mesmas contas de origem/destino e mesmo
        // valor). idempotency_key tem UNIQUE global na tabela, então uma chave reusada para uma
        // operação diferente — outro tipo (depósito depois saque), outro valor ou outras contas
        // — não pode ser tratada como replay: seria devolver 201 com o corpo de uma operação que
        // não aconteceu. `description` é deliberadamente excluído dessa comparação: um retry
        // legítimo reenvia o corpo idêntico, e uma diferença só na descrição já devolve a
        // transação armazenada, que a própria resposta revela.
        var existing = transactionRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            var tx = existing.get();
            var sameOperation = tx.getType() == type
                    && tx.getSourceAccount().getId().equals(sourceId)
                    && tx.getTargetAccount().getId().equals(targetId)
                    && tx.getAmount().compareTo(amount) == 0;

            if (!sameOperation) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "Idempotency key already used for a different operation");
            }

            log.info("Idempotent request, returning existing tx: {}", idempotencyKey);
            return TransactionResponse.from(tx);
        }

        if (sourceId.equals(targetId)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Source and target accounts must be different");
        }

        // Garante ordem de lock consistente (menor UUID primeiro) para evitar deadlock
        var firstLockId  = sourceId.compareTo(targetId) < 0 ? sourceId : targetId;
        var secondLockId = sourceId.compareTo(targetId) < 0 ? targetId : sourceId;

        var firstAccount  = accountRepository.findByIdWithLock(firstLockId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Account not found"));
        var secondAccount = accountRepository.findByIdWithLock(secondLockId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Account not found"));

        var source = firstLockId.equals(sourceId) ? firstAccount : secondAccount;
        var target = firstLockId.equals(targetId) ? firstAccount : secondAccount;

        if (!source.getCurrency().equals(target.getCurrency())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Source and target accounts must have the same currency");
        }

        if (!source.isSystem() && source.getBalance().compareTo(amount) < 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Insufficient balance to complete the transfer");
        }

        var transaction = Transaction.create(
                source, target, amount, source.getCurrency(),
                type, idempotencyKey, description);
        transactionRepository.save(transaction);

        source.debit(amount);
        target.credit(amount);

        ledgerService.recordTransfer(transaction, source, target, amount);
        transaction.complete();

        log.info("{} completed: {} -> {} amount={} tx={}",
                type, source.getId(), target.getId(), amount, transaction.getId());

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
