package com.sakupay.statement;

import com.sakupay.account.AccountRepository;
import com.sakupay.common.error.ApiException;
import com.sakupay.ledger.EntryDirection;
import com.sakupay.ledger.TransactionStatus;
import com.sakupay.ledger.TransactionType;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Account statements (mutasi rekening), read straight from the ledger. Days are business days in
 * {@code sakupay.business-zone}; {@code to} is inclusive. Everything is read in one REPEATABLE READ snapshot, so
 * opening balance, entries and closing balance always agree even while money is moving.
 */
@Service
public class StatementService {

    static final int MAX_DAYS = 366;
    static final int MAX_EXPORT_LINES = 10_000;

    /**
     * The counterparty is the other end of the business transaction (sender or receiver), not the other ledger
     * entry: a held transfer has more than two entries, and its HOLD leg is not something a customer should see.
     */
    private static final String LINES = """
            SELECT e.created_at, t.reference, t.type, t.status, e.direction, e.amount, e.balance_after, t.description,
                   CASE WHEN ca.type = 'CUSTOMER' THEN ca.account_number END AS counterparty
            FROM ledger_entries e
            JOIN transactions t ON t.id = e.transaction_id
            JOIN accounts ca ON ca.id = CASE WHEN e.account_id = t.source_account_id
                                             THEN t.destination_account_id ELSE t.source_account_id END
            WHERE e.account_id = ? AND e.created_at >= ? AND e.created_at < ?
            ORDER BY e.id
            LIMIT ? OFFSET ?
            """;

    private final AccountRepository accounts;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ZoneId zone;

    StatementService(AccountRepository accounts, JdbcTemplate jdbc, Clock clock,
                     @Value("${sakupay.business-zone}") ZoneId zone) {
        this.accounts = accounts;
        this.jdbc = jdbc;
        this.clock = clock;
        this.zone = zone;
    }

    public record Period(LocalDate from, LocalDate to) {
    }

    /** {@code status} is the transaction's current status, e.g. a debit that is still ON_HOLD or was REJECTED. */
    public record Line(Instant date, String reference, TransactionType type, TransactionStatus status,
                       EntryDirection direction, long amount, long balanceAfter, String description,
                       String counterparty) {
    }

    public record Statement(String accountNumber, LocalDate from, LocalDate to, ZoneId timeZone,
                            long openingBalance, long closingBalance, long totalCredits, long totalDebits,
                            int page, int size, long totalLines, List<Line> lines) {
    }

    /** Defaults to the last 30 days; at most {@value #MAX_DAYS} days. */
    public Period period(LocalDate from, LocalDate to) {
        LocalDate end = to != null ? to : LocalDate.now(clock.withZone(zone));
        LocalDate start = from != null ? from : end.minusDays(29);
        if (start.isAfter(end)) {
            throw ApiException.badRequest("INVALID_PERIOD", "'from' must not be after 'to'.");
        }
        if (ChronoUnit.DAYS.between(start, end) >= MAX_DAYS) {
            throw ApiException.badRequest("INVALID_PERIOD", "A statement covers at most " + MAX_DAYS + " days.");
        }
        return new Period(start, end);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Statement statement(long userId, String accountNumber, Period period, int page, int size) {
        long accountId = accounts.findIdByAccountNumberAndOwnerId(accountNumber, userId)
                .orElseThrow(() -> ApiException.notFound("ACCOUNT_NOT_FOUND", "Account not found."));
        Timestamp start = Timestamp.from(period.from().atStartOfDay(zone).toInstant());
        Timestamp end = Timestamp.from(period.to().plusDays(1).atStartOfDay(zone).toInstant());

        long opening = balanceBefore(accountId, start, 0L);
        long closing = balanceBefore(accountId, end, opening);
        var totals = jdbc.queryForMap("""
                SELECT count(*) AS lines,
                       COALESCE(SUM(amount) FILTER (WHERE direction = 'CREDIT'), 0) AS credits,
                       COALESCE(SUM(amount) FILTER (WHERE direction = 'DEBIT'), 0) AS debits
                FROM ledger_entries WHERE account_id = ? AND created_at >= ? AND created_at < ?
                """, accountId, start, end);
        List<Line> lines = jdbc.query(LINES, (rs, i) -> new Line(
                        rs.getTimestamp(1).toInstant(), rs.getString(2), TransactionType.valueOf(rs.getString(3)),
                        TransactionStatus.valueOf(rs.getString(4)), EntryDirection.valueOf(rs.getString(5)),
                        rs.getLong(6), rs.getLong(7), rs.getString(8), rs.getString(9)),
                accountId, start, end, size, (long) page * size);

        return new Statement(accountNumber, period.from(), period.to(), zone, opening, closing,
                ((Number) totals.get("credits")).longValue(), ((Number) totals.get("debits")).longValue(),
                page, size, ((Number) totals.get("lines")).longValue(), lines);
    }

    /** The whole period in one go, for CSV / PDF export (its own snapshot: a self-call would skip the proxy). */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Statement export(long userId, String accountNumber, Period period) {
        Statement statement = statement(userId, accountNumber, period, 0, MAX_EXPORT_LINES + 1);
        if (statement.totalLines() > MAX_EXPORT_LINES) {
            throw ApiException.badRequest("PERIOD_TOO_LARGE",
                    "More than " + MAX_EXPORT_LINES + " lines; export a shorter period.");
        }
        return statement;
    }

    public ZoneId zone() {
        return zone;
    }

    private long balanceBefore(long accountId, Timestamp moment, long fallback) {
        List<Long> last = jdbc.queryForList("""
                SELECT balance_after FROM ledger_entries WHERE account_id = ? AND created_at < ?
                ORDER BY id DESC LIMIT 1
                """, Long.class, accountId, moment);
        return last.isEmpty() ? fallback : last.get(0);
    }
}
