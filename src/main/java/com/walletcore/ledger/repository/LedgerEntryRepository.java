package com.walletcore.ledger.repository;

import com.walletcore.ledger.entity.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {
    List<LedgerEntry> findAllByTransactionIdOrderByCreatedAtAsc(UUID transactionId);
}
