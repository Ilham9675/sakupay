package com.sakupay.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sakupay.Api;
import com.sakupay.Api.Session;
import com.sakupay.IntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The database protects the books even from code that bypasses the application. */
@IntegrationTest
class LedgerIntegrityTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void aTransactionWhoseDebitsAndCreditsDifferCannotBeCommitted() throws Exception {
        Api api = new Api(mvc, objectMapper);
        Session user = api.newUserWithPin();
        String customerAccount = api.openAccount(user);
        long settlementId = jdbc.queryForObject("select id from accounts where account_number = '1000000001'", Long.class);
        long accountId = jdbc.queryForObject("select id from accounts where account_number = ?", Long.class, customerAccount);
        long userId = jdbc.queryForObject("select id from users where email = 'admin@test.local'", Long.class);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            Long txId = jdbc.queryForObject("""
                    INSERT INTO transactions (reference, type, status, amount, source_account_id, destination_account_id, initiated_by)
                    VALUES (?, 'DEPOSIT', 'COMPLETED', 500, ?, ?, ?) RETURNING id
                    """, Long.class, UUID.randomUUID().toString(), settlementId, accountId, userId);
            // Only one side of the double entry: money created out of nothing
            jdbc.update("INSERT INTO ledger_entries (transaction_id, account_id, direction, amount, balance_after) VALUES (?, ?, 'CREDIT', 500, 500)",
                    txId, accountId);
        })).rootCause().hasMessageContaining("not balanced");
    }

    @Test
    void ledgerEntriesCannotBeEditedAndCustomerBalancesCannotGoNegative() throws Exception {
        Api api = new Api(mvc, objectMapper);
        Session user = api.newUserWithPin();
        String account = api.openAccount(user);
        api.deposit(account, 10_000);

        assertThatThrownBy(() -> jdbc.update("UPDATE ledger_entries SET amount = amount * 100"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("insert-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM ledger_entries"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("insert-only");
        assertThatThrownBy(() -> jdbc.update("UPDATE accounts SET balance = -1 WHERE account_number = ?", account))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("non_negative");
    }

    @Test
    void reconciliationProvesTheBooksBalanceAndIsForAuditorsOnly() throws Exception {
        Api api = new Api(mvc, objectMapper);
        Session user = api.newUserWithPin();
        String account = api.openAccount(user);
        api.deposit(account, 42_000);

        api.get("/api/v1/reconciliation", user.accessToken()).andExpect(status().isForbidden());
        api.get("/api/v1/reconciliation", api.admin().accessToken()).andExpect(status().isForbidden());

        Session auditor = api.newUser();
        api.put("/api/v1/admin/users/" + auditor.userId() + "/role", api.admin().accessToken(), Map.of("role", "AUDITOR"))
                .andExpect(status().isOk());
        auditor = api.login(auditor.userId(), auditor.email(), Api.PASSWORD);

        JsonNode report = api.body(api.get("/api/v1/reconciliation", auditor.accessToken()).andExpect(status().isOk()));
        assertThat(report.get("healthy").asBoolean()).as(report.toString()).isTrue();
        assertThat(report.get("sumOfAllBalances").asLong()).isZero();
        assertThat(report.get("mismatches")).isEmpty();
        assertThat(report.get("unbalancedTransactions").asInt()).isZero();
        assertThat(report.get("transactionsChecked").asInt()).isPositive();
    }
}
