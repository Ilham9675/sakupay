package com.sakupay.ledger;

import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;

/** No update or delete methods: entries are history. */
public interface LedgerEntryRepository extends Repository<LedgerEntry, Long> {

    LedgerEntry save(LedgerEntry entry);

    List<LedgerEntry> findByTransactionIdOrderById(Long transactionId);

    Optional<LedgerEntry> findByTransactionIdAndAccountId(Long transactionId, Long accountId);
}
