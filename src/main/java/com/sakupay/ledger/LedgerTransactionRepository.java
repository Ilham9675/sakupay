package com.sakupay.ledger;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Optional;

public interface LedgerTransactionRepository extends JpaRepository<LedgerTransaction, Long> {

    Optional<LedgerTransaction> findByReference(String reference);

    /** Outgoing transfers of one account since a moment (start of the business day), for the daily limit. */
    @Query("""
            select coalesce(sum(t.amount), 0) from LedgerTransaction t
            where t.sourceAccountId = :accountId and t.type = com.sakupay.ledger.TransactionType.TRANSFER
              and t.createdAt >= :since
            """)
    long sumOutgoingTransfersSince(Long accountId, Instant since);
}
