package com.sakupay.fraud;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sakupay.Api;
import com.sakupay.Api.Session;
import com.sakupay.TestcontainersConfiguration;
import com.sakupay.common.error.ApiException;
import com.sakupay.ledger.ReconciliationService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fraud rules with production thresholds (Rp5.000.000 large, Rp2.000.000 new payee, more than 5 transfers in
 * 10 minutes). Only the night rule stays off, because the test cannot choose the time of day it runs at;
 * it is covered by FraudRulesTest instead.
 */
@SpringBootTest(properties = "sakupay.fraud.velocity-max-transfers=5")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class FraudTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ReconciliationService reconciliation;

    @Autowired
    private FraudReviewService reviews;

    private Api api;
    private Session alice;
    private Session bob;
    private String a;
    private String b;
    private String admin;

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc, objectMapper);
        alice = api.newUserWithPin();
        bob = api.newUserWithPin();
        a = api.openAccount(alice);
        b = api.openAccount(bob);
        admin = api.admin().accessToken();
    }

    @AfterEach
    void booksStillBalance() {
        // Held money sits in the HOLD account, so the books balance at every step of the review
        assertThat(reconciliation.run().healthy()).isTrue();
    }

    @Test
    void aLargeTransferToANewPayeeIsHeldAndTheMoneyReserved() throws Exception {
        api.deposit(a, 10_000_000);

        JsonNode tx = api.body(api.transfer(alice, key(), a, b, 6_000_000).andExpect(status().isCreated()));

        assertThat(tx.get("status").asText()).isEqualTo("ON_HOLD");
        assertThat(tx.get("holdReasons")).extracting(JsonNode::asText).containsExactly("LARGE_AMOUNT", "NEW_PAYEE_LARGE");
        assertThat(tx.get("balanceAfter").asLong()).isEqualTo(4_000_000);
        assertThat(api.balance(alice, a)).isEqualTo(4_000_000);
        assertThat(api.balance(bob, b)).isZero();
        // Reserved money cannot be spent again while it waits
        api.transfer(alice, key(), a, b, 4_500_000).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));

        String ref = tx.get("reference").asText();
        api.get("/api/v1/transfers/" + ref, alice.accessToken()).andExpect(jsonPath("$.status").value("ON_HOLD"));
        api.get("/api/v1/transfers/" + ref, bob.accessToken()).andExpect(status().isNotFound());

        JsonNode alert = alertFor(ref);
        assertThat(alert.get("status").asText()).isEqualTo("OPEN");
        assertThat(alert.get("severity").asText()).isEqualTo("HIGH");
        api.get("/api/v1/fraud-alerts", alice.accessToken()).andExpect(status().isForbidden());
    }

    @Test
    void approvingReleasesTheMoneyToTheReceiverExactlyOnce() throws Exception {
        api.deposit(a, 10_000_000);
        String key = key();
        String ref = api.body(api.transfer(alice, key, a, b, 6_000_000)).get("reference").asText();
        long alertId = alertFor(ref).get("id").asLong();

        api.post("/api/v1/admin/fraud-alerts/" + alertId + "/approve", admin, Map.of("note", "Customer confirmed by phone"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.reviewNote").value("Customer confirmed by phone"));

        assertThat(api.balance(bob, b)).isEqualTo(6_000_000);
        assertThat(api.balance(alice, a)).isEqualTo(4_000_000);
        api.get("/api/v1/transfers/" + ref, alice.accessToken()).andExpect(jsonPath("$.status").value("COMPLETED"));
        // A retry of the original request now reports the current status
        api.transfer(alice, key, a, b, 6_000_000).andExpect(jsonPath("$.status").value("COMPLETED"));
        // Decided once, for good
        api.post("/api/v1/admin/fraud-alerts/" + alertId + "/approve", admin, null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALERT_ALREADY_REVIEWED"));
        api.post("/api/v1/admin/fraud-alerts/" + alertId + "/reject", admin, Map.of("note", "too late"))
                .andExpect(status().isConflict());

        // Bob's statement shows a normal incoming transfer from Alice; the HOLD leg is invisible to customers
        JsonNode line = statement(bob, b).get("lines").get(0);
        assertThat(line.get("direction").asText()).isEqualTo("CREDIT");
        assertThat(line.get("amount").asLong()).isEqualTo(6_000_000);
        assertThat(line.get("counterparty").asText()).isEqualTo(a);
        assertThat(line.get("status").asText()).isEqualTo("COMPLETED");
    }

    @Test
    void rejectingNeedsAReasonAndGivesTheMoneyBack() throws Exception {
        api.deposit(a, 10_000_000);
        String ref = api.body(api.transfer(alice, key(), a, b, 7_000_000)).get("reference").asText();
        long alertId = alertFor(ref).get("id").asLong();

        api.post("/api/v1/admin/fraud-alerts/" + alertId + "/reject", admin, null)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("NOTE_REQUIRED"));
        api.post("/api/v1/admin/fraud-alerts/" + alertId + "/reject", admin, Map.of("note", "Receiver reported as mule account"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));

        assertThat(api.balance(alice, a)).isEqualTo(10_000_000);
        assertThat(api.balance(bob, b)).isZero();
        api.get("/api/v1/transfers/" + ref, alice.accessToken()).andExpect(jsonPath("$.status").value("REJECTED"));
        // Alice sees the money leave and come back, both marked REJECTED
        JsonNode lines = statement(alice, a).get("lines");
        assertThat(lines).extracting(l -> l.get("direction").asText() + ":" + l.get("status").asText())
                .containsExactly("CREDIT:COMPLETED", "DEBIT:REJECTED", "CREDIT:REJECTED");
    }

    @Test
    void theSixthTransferWithinTenMinutesIsHeld() throws Exception {
        api.deposit(a, 100_000);

        for (int i = 0; i < 5; i++) {
            api.transfer(alice, key(), a, b, 1_000).andExpect(jsonPath("$.status").value("COMPLETED"));
        }
        api.transfer(alice, key(), a, b, 1_000)
                .andExpect(jsonPath("$.status").value("ON_HOLD"))
                .andExpect(jsonPath("$.holdReasons[0]").value("HIGH_VELOCITY"));
    }

    @Test
    void aSizeableTransferIsOnlySuspiciousForAPayeeNeverPaidBefore() throws Exception {
        Session carol = api.newUserWithPin();
        String c = api.openAccount(carol);
        api.deposit(a, 10_000_000);

        api.transfer(alice, key(), a, b, 100_000).andExpect(jsonPath("$.status").value("COMPLETED"));
        api.transfer(alice, key(), a, b, 3_000_000).andExpect(jsonPath("$.status").value("COMPLETED"));
        api.transfer(alice, key(), a, c, 3_000_000)
                .andExpect(jsonPath("$.status").value("ON_HOLD"))
                .andExpect(jsonPath("$.holdReasons.length()").value(1))
                .andExpect(jsonPath("$.holdReasons[0]").value("NEW_PAYEE_LARGE"));
    }

    @Test
    void anAdminCannotApproveTheirOwnTransfer() throws Exception {
        Session firstAdmin = api.admin();
        long firstAdminId = api.body(api.get("/api/v1/me", firstAdmin.accessToken())).get("id").asLong();
        api.put("/api/v1/me/pin", firstAdmin.accessToken(), Map.of("password", Api.ADMIN_PASSWORD, "pin", Api.PIN));
        String adminAccount = api.openAccount(firstAdmin);
        api.deposit(adminAccount, 6_000_000);
        String ref = api.body(api.transfer(firstAdmin, key(), adminAccount, b, 6_000_000)).get("reference").asText();
        long alertId = alertFor(ref).get("id").asLong();

        api.post("/api/v1/admin/fraud-alerts/" + alertId + "/approve", firstAdmin.accessToken(), null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SELF_REVIEW"));

        Session secondAdmin = promote(api.newUser(), "ADMIN");
        api.post("/api/v1/admin/fraud-alerts/" + alertId + "/approve", secondAdmin.accessToken(), null)
                .andExpect(status().isOk());
        assertThat(api.balance(bob, b)).isEqualTo(6_000_000);
        assertThat(firstAdminId).isPositive();
    }

    @Test
    void twoAdminsDecidingTheSameAlertAtOnceCannotBothWin() throws Exception {
        api.deposit(a, 10_000_000);
        String ref = api.body(api.transfer(alice, key(), a, b, 6_000_000)).get("reference").asText();
        long alertId = alertFor(ref).get("id").asLong();
        long adminOne = promote(api.newUser(), "ADMIN").userId();
        long adminTwo = promote(api.newUser(), "ADMIN").userId();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        try {
            // One approves, the other rejects, at the same instant
            results.add(pool.submit(() -> decide(start, () -> reviews.approve(adminOne, alertId, "ok"))));
            results.add(pool.submit(() -> decide(start, () -> reviews.reject(adminTwo, alertId, "no"))));
            start.countDown();
            List<String> outcomes = new ArrayList<>();
            for (Future<String> r : results) {
                outcomes.add(r.get());
            }
            assertThat(outcomes).filteredOn("ALERT_ALREADY_REVIEWED"::equals).hasSize(1);
            assertThat(outcomes).filteredOn(o -> o.equals("APPROVED") || o.equals("REJECTED")).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
        // Either Bob got the money or Alice got it back; never both, never neither
        assertThat(api.balance(alice, a) + api.balance(bob, b)).isEqualTo(10_000_000);
    }

    @Test
    void auditorsCanReadAlertsButNotDecide() throws Exception {
        api.deposit(a, 10_000_000);
        String ref = api.body(api.transfer(alice, key(), a, b, 6_000_000)).get("reference").asText();
        long alertId = alertFor(ref).get("id").asLong();
        Session auditor = promote(api.newUser(), "AUDITOR");

        api.get("/api/v1/fraud-alerts?status=OPEN", auditor.accessToken()).andExpect(status().isOk());
        api.post("/api/v1/admin/fraud-alerts/" + alertId + "/approve", auditor.accessToken(), null)
                .andExpect(status().isForbidden());
    }

    // ---- helpers ----

    private interface Decision {
        FraudReviewService.AlertView run();
    }

    private static String decide(CountDownLatch start, Decision decision) throws InterruptedException {
        start.await();
        try {
            return decision.run().status().name();
        } catch (ApiException e) {
            return e.getCode();
        }
    }

    private JsonNode alertFor(String reference) throws Exception {
        JsonNode page = api.body(api.get("/api/v1/fraud-alerts?status=OPEN&size=200", admin).andExpect(status().isOk()));
        for (JsonNode alert : page.get("content")) {
            if (alert.get("transactionReference").asText().equals(reference)) {
                return alert;
            }
        }
        throw new AssertionError("No open alert for " + reference);
    }

    private Session promote(Session user, String role) throws Exception {
        api.put("/api/v1/admin/users/" + user.userId() + "/role", admin, Map.of("role", role)).andExpect(status().isOk());
        return api.login(user.userId(), user.email(), Api.PASSWORD);
    }

    private JsonNode statement(Session user, String account) throws Exception {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Jakarta"));
        ResultActions result = api.get("/api/v1/accounts/" + account + "/statement?from=" + today + "&to=" + today,
                user.accessToken());
        return api.body(result.andExpect(status().isOk()));
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }
}
