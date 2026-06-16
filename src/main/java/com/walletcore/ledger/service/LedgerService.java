package com.walletcore.ledger.service;

import com.walletcore.account.entity.Account;
import com.walletcore.ledger.entity.LedgerEntry;
import com.walletcore.ledger.repository.LedgerEntryRepository;
import com.walletcore.transaction.entity.Transaction;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
public class LedgerService {

    private final LedgerEntryRepository ledgerEntryRepository;

    public LedgerService(LedgerEntryRepository ledgerEntryRepository) {
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    public void recordTransfer(Transaction transaction, Account source, Account target, BigDecimal amount) {
        // Débito na conta origem (saldo já foi decrementado antes de chamar este método)
        var debitEntry = LedgerEntry.of(transaction, source,
                LedgerEntry.EntryType.DEBIT, amount, source.getBalance());

        // Crédito na conta destino (saldo já foi incrementado antes de chamar este método)
        var creditEntry = LedgerEntry.of(transaction, target,
                LedgerEntry.EntryType.CREDIT, amount, target.getBalance());

        ledgerEntryRepository.save(debitEntry);
        ledgerEntryRepository.save(creditEntry);
    }
}
