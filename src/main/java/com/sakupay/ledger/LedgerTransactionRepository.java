package com.sakupay.ledger;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Optional;

public interface LedgerTransactionRepository extends JpaRepository<LedgerTransaction, Long> {

    Optional<LedgerTransaction> findByReference(String reference);

    /**
     * Outgoing transfers of one account since a moment (start of the business day), for the daily limit.
     * Held transfers count (the money is reserved); rejected ones do not.
     */
    @Query("""
            select coalesce(sum(t.amount), 0) from LedgerTransaction t
            where t.sourceAccountId = :accountId and t.type = com.sakupay.ledger.TransactionType.TRANSFER
              and t.status <> com.sakupay.ledger.TransactionStatus.REJECTED
              and t.createdAt >= :since
            """)
    long sumOutgoingTransfersSince(Long accountId, Instant since);

    /** Transfers started from one account since a moment, whatever their outcome (velocity rule). */
    @Query("""
            select count(t) from LedgerTransaction t
            where t.sourceAccountId = :accountId and t.type = com.sakupay.ledger.TransactionType.TRANSFER
              and t.createdAt >= :since
            """)
    long countTransfersSince(Long accountId, Instant since);

    /** Has this sender ever completed a transfer to this receiver? (new payee rule) */
    boolean existsBySourceAccountIdAndDestinationAccountIdAndTypeAndStatus(Long sourceAccountId,
            Long destinationAccountId, TransactionType type, TransactionStatus status);
}
