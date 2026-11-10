package com.sakupay.ledger;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Checks the books against themselves, the way an end-of-day job at a bank would:
 * <ol>
 *   <li>every stored balance equals the sum of that account's ledger entries,</li>
 *   <li>every transaction's debits equal its credits,</li>
 *   <li>all balances together (customers + SETTLEMENT) add up to exactly zero.</li>
 * </ol>
 * Runs as one REPEATABLE READ snapshot, so transfers happening meanwhile cannot produce false alarms.
 */
@Service
public class ReconciliationService {

    private static final String SIGNED = "CASE e.direction WHEN 'CREDIT' THEN e.amount ELSE -e.amount END";

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public ReconciliationService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Report run() {
        List<Mismatch> mismatches = jdbc.query("""
                SELECT a.account_number, a.balance, COALESCE(SUM(%s), 0) AS ledger_balance
                FROM accounts a LEFT JOIN ledger_entries e ON e.account_id = a.id
                GROUP BY a.id, a.account_number, a.balance
                HAVING a.balance <> COALESCE(SUM(%s), 0)
                ORDER BY a.account_number
                """.formatted(SIGNED, SIGNED),
                (rs, i) -> new Mismatch(rs.getString(1), rs.getLong(2), rs.getLong(3)));
        Integer unbalanced = jdbc.queryForObject("""
                SELECT count(*) FROM (
                    SELECT e.transaction_id FROM ledger_entries e GROUP BY e.transaction_id HAVING SUM(%s) <> 0
                ) t
                """.formatted(SIGNED), Integer.class);
        Long total = jdbc.queryForObject("SELECT COALESCE(SUM(balance), 0) FROM accounts", Long.class);
        Integer accounts = jdbc.queryForObject("SELECT count(*) FROM accounts", Integer.class);
        Integer transactions = jdbc.queryForObject("SELECT count(*) FROM transactions", Integer.class);

        boolean healthy = mismatches.isEmpty() && unbalanced == 0 && total == 0;
        return new Report(Instant.now(clock), accounts, transactions, total, unbalanced, mismatches, healthy);
    }

    public record Report(Instant checkedAt, int accountsChecked, int transactionsChecked, long sumOfAllBalances,
                         int unbalancedTransactions, List<Mismatch> mismatches, boolean healthy) {
    }

    public record Mismatch(String accountNumber, long storedBalance, long ledgerBalance) {
    }
}
