package com.sakupay.ledger;

import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;

/** No update or delete methods: entries are history. */
public interface LedgerEntryRepository extends Repository<LedgerEntry, Long> {

    LedgerEntry save(LedgerEntry entry);

    List<LedgerEntry> findByTransactionIdOrderById(Long transactionId);

    /** The first entry of a transaction on one account (a held transfer that was rejected has two on the sender). */
    Optional<LedgerEntry> findFirstByTransactionIdAndAccountIdOrderById(Long transactionId, Long accountId);
}
