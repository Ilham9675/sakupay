package com.sakupay.transfer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sakupay.Api;
import com.sakupay.Api.Session;
import com.sakupay.IntegrationTest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IntegrationTest
class TransferTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    private Api api;
    private Session alice;
    private Session bob;
    private String aliceAccount;
    private String bobAccount;

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc, objectMapper);
        alice = api.newUserWithPin();
        bob = api.newUserWithPin();
        aliceAccount = api.openAccount(alice);
        bobAccount = api.openAccount(bob);
    }

    @Test
    void aTransferIsTwoBalancedLedgerEntries() throws Exception {
        api.deposit(aliceAccount, 1_000_000);

        JsonNode tx = api.body(api.transfer(alice, key(), aliceAccount, bobAccount, 250_000)
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed")));

        assertThat(tx.get("type").asText()).isEqualTo("TRANSFER");
        assertThat(tx.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(tx.get("balanceAfter").asLong()).isEqualTo(750_000);
        assertThat(api.balance(alice, aliceAccount)).isEqualTo(750_000);
        assertThat(api.balance(bob, bobAccount)).isEqualTo(250_000);

        List<Map<String, Object>> entries = jdbc.queryForList("""
                SELECT a.account_number, e.direction, e.amount, e.balance_after
                FROM ledger_entries e JOIN transactions t ON t.id = e.transaction_id JOIN accounts a ON a.id = e.account_id
                WHERE t.reference = ? ORDER BY e.id
                """, tx.get("reference").asText());
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0)).containsEntry("account_number", aliceAccount).containsEntry("direction", "DEBIT")
                .containsEntry("amount", 250_000L).containsEntry("balance_after", 750_000L);
        assertThat(entries.get(1)).containsEntry("account_number", bobAccount).containsEntry("direction", "CREDIT")
                .containsEntry("amount", 250_000L).containsEntry("balance_after", 250_000L);
    }

    @Test
    void invalidTransfersAreRejectedBeforeMoneyMoves() throws Exception {
        api.deposit(aliceAccount, 100_000);
        Session mallory = api.newUserWithPin();
        String typo = bobAccount.substring(0, 9) + (char) ('0' + (bobAccount.charAt(9) - '0' + 1) % 10);

        api.transfer(alice, null, aliceAccount, bobAccount, 1_000)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        api.transfer(alice, key(), aliceAccount, typo, 1_000)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ACCOUNT_NUMBER"));
        api.transfer(alice, key(), aliceAccount, aliceAccount, 1_000)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SAME_ACCOUNT"));
        api.transfer(alice, key(), aliceAccount, bobAccount, 0)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        api.transfer(alice, key(), aliceAccount, bobAccount, 1_000, "135790")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("WRONG_PIN"));
        api.transfer(alice, key(), aliceAccount, validButUnused(), 1_000)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("DESTINATION_NOT_FOUND"));
        api.transfer(alice, key(), aliceAccount, bobAccount, 100_001)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));
        // IDOR: Mallory names Alice's account as the source, with Mallory's own valid PIN
        api.transfer(mallory, key(), aliceAccount, bobAccount, 1_000)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));

        assertThat(api.balance(alice, aliceAccount)).isEqualTo(100_000);
        assertThat(api.balance(bob, bobAccount)).isZero();
    }

    @Test
    void theDailyLimitCountsEverythingSentToday() throws Exception {
        api.deposit(aliceAccount, 15_000_000);

        api.transfer(alice, key(), aliceAccount, bobAccount, 6_000_000).andExpect(status().isCreated());
        api.transfer(alice, key(), aliceAccount, bobAccount, 5_000_000)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DAILY_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.remainingToday").value(4_000_000));
        api.transfer(alice, key(), aliceAccount, bobAccount, 4_000_000).andExpect(status().isCreated());
        assertThat(api.balance(alice, aliceAccount)).isEqualTo(5_000_000);
    }

    @Test
    void retryingWithTheSameKeyReturnsTheSameResultAndMovesMoneyOnce() throws Exception {
        api.deposit(aliceAccount, 100_000);
        String key = key();

        JsonNode first = api.body(api.transfer(alice, key, aliceAccount, bobAccount, 30_000).andExpect(status().isCreated()));
        JsonNode retry = api.body(api.transfer(alice, key, aliceAccount, bobAccount, 30_000)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true")));

        assertThat(retry).isEqualTo(first);
        assertThat(api.balance(alice, aliceAccount)).isEqualTo(70_000);
        assertThat(api.balance(bob, bobAccount)).isEqualTo(30_000);
        // The same key for a different request is a client bug, not a retry
        api.transfer(alice, key, aliceAccount, bobAccount, 99_000)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void aFailedTransferDoesNotUseUpItsKey() throws Exception {
        String key = key();
        api.transfer(alice, key, aliceAccount, bobAccount, 50_000)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));

        api.deposit(aliceAccount, 50_000);
        api.transfer(alice, key, aliceAccount, bobAccount, 50_000)
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"));
        assertThat(api.balance(bob, bobAccount)).isEqualTo(50_000);
    }

    @Test
    void onlyAdminsRecordDepositsAndTheyAreIdempotentToo() throws Exception {
        api.post("/api/v1/admin/accounts/" + aliceAccount + "/deposits", alice.accessToken(), Map.of("amount", 1_000))
                .andExpect(status().isForbidden());

        String admin = api.admin().accessToken();
        String key = key();
        for (int i = 0; i < 2; i++) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .post("/api/v1/admin/accounts/" + aliceAccount + "/deposits")
                            .header("Authorization", "Bearer " + admin).header("Idempotency-Key", key)
                            .contentType("application/json").content("{\"amount\": 75000, \"description\": \"Setor tunai\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.type").value("DEPOSIT"))
                    .andExpect(jsonPath("$.sourceAccount").value(TransferService.SETTLEMENT_ACCOUNT));
        }
        assertThat(api.balance(alice, aliceAccount)).isEqualTo(75_000);
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    /** A number with a correct check digit that nobody owns. */
    private String validButUnused() {
        for (int i = 0; ; i++) {
            String candidate = com.sakupay.account.AccountNumbers.generate(new java.util.Random(i));
            Integer used = jdbc.queryForObject("select count(*) from accounts where account_number = ?", Integer.class, candidate);
            if (used == 0) {
                return candidate;
            }
        }
    }
}
