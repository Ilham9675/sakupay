package com.sakupay.transfer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.sakupay.Api;
import com.sakupay.Api.Session;
import com.sakupay.TestcontainersConfiguration;
import com.sakupay.ledger.ReconciliationService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real HTTP requests against the running server (Tomcat + HikariCP with 10 connections + PostgreSQL), all
 * released at the same instant. This is where lost updates, overdrafts and deadlocks would show up.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ConcurrentTransferTest {

    private static final int REQUESTS = 100;

    @LocalServerPort
    private int port;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ReconciliationService reconciliation;

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10)).build();
    private ExecutorService pool;
    private Api api;

    @BeforeEach
    void setUp() {
        api = new Api(mvc, json);
        pool = Executors.newFixedThreadPool(REQUESTS);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
        // Whatever happened, the books must still balance
        assertThat(reconciliation.run().healthy()).isTrue();
    }

    @Test
    void hundredSimultaneousTransfersNeverOverdrawTheAccount() throws Exception {
        Session alice = api.newUserWithPin();
        Session bob = api.newUserWithPin();
        String from = api.openAccount(alice);
        String to = api.openAccount(bob);
        api.deposit(from, 500_000);

        // 100 transfers of Rp10.000 from an account holding Rp500.000: exactly 50 can succeed
        long started = System.nanoTime();
        List<HttpResponse<String>> responses = fire(REQUESTS, i -> transfer(alice, UUID.randomUUID().toString(), from, to, 10_000));
        long millis = (System.nanoTime() - started) / 1_000_000;

        assertThat(responses).filteredOn(r -> r.statusCode() == 201).hasSize(50);
        assertThat(responses).filteredOn(r -> r.statusCode() == 409 && r.body().contains("INSUFFICIENT_FUNDS")).hasSize(50);
        assertThat(api.balance(alice, from)).isZero();
        assertThat(api.balance(bob, to)).isEqualTo(500_000);
        // Every successful response saw a different, consistent balance: 490.000, 480.000, ..., 0
        assertThat(responses).filteredOn(r -> r.statusCode() == 201)
                .extracting(r -> read(r).get("balanceAfter").asLong())
                .containsExactlyInAnyOrderElementsOf(range(0, 490_000, 10_000));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM ledger_entries e JOIN accounts a ON a.id = e.account_id
                WHERE a.account_number = ? AND e.direction = 'DEBIT'
                """, Integer.class, from)).isEqualTo(50);
        System.out.printf("%d concurrent transfer requests finished in %d ms%n", REQUESTS, millis);
    }

    @Test
    void transfersInOppositeDirectionsDoNotDeadlock() throws Exception {
        Session alice = api.newUserWithPin();
        Session bob = api.newUserWithPin();
        String a = api.openAccount(alice);
        String b = api.openAccount(bob);
        api.deposit(a, 1_000_000);
        api.deposit(b, 1_000_000);

        // Half A->B, half B->A at the same time: without a fixed lock order these would deadlock
        List<HttpResponse<String>> responses = fire(REQUESTS, i -> i % 2 == 0
                ? transfer(alice, UUID.randomUUID().toString(), a, b, 1_000)
                : transfer(bob, UUID.randomUUID().toString(), b, a, 1_000));

        assertThat(responses).allSatisfy(r -> assertThat(r.statusCode()).as(r.body()).isEqualTo(201));
        assertThat(api.balance(alice, a)).isEqualTo(1_000_000);
        assertThat(api.balance(bob, b)).isEqualTo(1_000_000);
    }

    @Test
    void theSameRequestSentTenTimesAtOnceMovesMoneyOnce() throws Exception {
        Session alice = api.newUserWithPin();
        Session bob = api.newUserWithPin();
        String from = api.openAccount(alice);
        String to = api.openAccount(bob);
        api.deposit(from, 100_000);
        String key = UUID.randomUUID().toString();

        // A client with a flaky connection retries aggressively, all with the same key
        List<HttpResponse<String>> responses = fire(10, i -> transfer(alice, key, from, to, 25_000));

        assertThat(responses).allSatisfy(r -> assertThat(r.statusCode()).as(r.body()).isEqualTo(201));
        assertThat(responses).extracting(r -> read(r).get("reference").asText()).containsOnly(read(responses.get(0)).get("reference").asText());
        assertThat(responses).filteredOn(r -> r.headers().firstValue("Idempotent-Replayed").isEmpty()).hasSize(1);
        assertThat(api.balance(alice, from)).isEqualTo(75_000);
        assertThat(api.balance(bob, to)).isEqualTo(25_000);
    }

    // ---- helpers ----

    private interface RequestFactory {
        HttpRequest create(int i) throws Exception;
    }

    /** Prepares all requests, then releases them at once from separate threads. */
    private List<HttpResponse<String>> fire(int count, RequestFactory factory) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<HttpResponse<String>>> futures = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            HttpRequest request = factory.create(i);
            Callable<HttpResponse<String>> call = () -> {
                start.await();
                return http.send(request, HttpResponse.BodyHandlers.ofString());
            };
            futures.add(pool.submit(call));
        }
        start.countDown();
        List<HttpResponse<String>> responses = new ArrayList<>();
        for (Future<HttpResponse<String>> f : futures) {
            responses.add(f.get());
        }
        return responses;
    }

    private HttpRequest transfer(Session user, String key, String from, String to, long amount) throws Exception {
        String body = json.writeValueAsString(Map.of("sourceAccount", from, "destinationAccount", to,
                "amount", amount, "pin", Api.PIN));
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/transfers"))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + user.accessToken())
                .header("Idempotency-Key", key)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private JsonNode read(HttpResponse<String> response) {
        try {
            return json.readTree(response.body());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<Long> range(long from, long to, long step) {
        List<Long> values = new ArrayList<>();
        for (long v = from; v <= to; v += step) {
            values.add(v);
        }
        return values;
    }
}
