package com.sakupay.topup;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.sakupay.Api;
import com.sakupay.Api.Session;
import com.sakupay.IntegrationTest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The partner bank's webhook: only genuine, fresh, first deliveries move money. */
@IntegrationTest
class TopupWebhookTest {

    static final String SECRET = "test-only-webhook-secret-32-bytes-min";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    private Api api;
    private Session owner;
    private String account;
    private String va;

    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc, objectMapper);
        owner = api.newUserWithPin();
        JsonNode opened = api.body(api.post("/api/v1/accounts", owner.accessToken(), null).andExpect(status().isCreated()));
        account = opened.get("accountNumber").asText();
        va = opened.get("virtualAccount").asText();
    }

    @Test
    void everyAccountHasAVirtualAccountAndAGenuineWebhookCreditsIt() throws Exception {
        assertThat(va).isEqualTo("7008" + account);

        JsonNode result = api.body(send(payload(eventId(), va, 50_000), Instant.now(), SECRET)
                .andExpect(status().isOk()));

        assertThat(result.get("status").asText()).isEqualTo("CREDITED");
        assertThat(api.balance(owner, account)).isEqualTo(50_000);
        assertThat(jdbc.queryForObject("select type from transactions where reference = ?", String.class,
                result.get("transactionReference").asText())).isEqualTo("TOPUP");
    }

    @Test
    void aRepeatedDeliveryOfTheSameEventCreditsOnce() throws Exception {
        String event = eventId();
        String body = payload(event, va, 75_000);

        JsonNode first = api.body(send(body, Instant.now(), SECRET).andExpect(status().isOk()));
        // The bank did not get our answer and retries later: new timestamp, new signature, same event
        JsonNode retry = api.body(send(body, Instant.now().plusSeconds(30), SECRET).andExpect(status().isOk()));

        assertThat(retry.get("status").asText()).isEqualTo("DUPLICATE");
        assertThat(retry.get("transactionReference")).isEqualTo(first.get("transactionReference"));
        assertThat(api.balance(owner, account)).isEqualTo(75_000);
        // The same event id with another amount is not a retry
        send(payload(event, va, 99_000), Instant.now(), SECRET)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EVENT_ID_CONFLICT"));
    }

    @Test
    void forgedTamperedAndReplayedWebhooksAreRejectedAndAudited() throws Exception {
        int auditedBefore = rejections();
        String body = payload(eventId(), va, 10_000);
        Instant now = Instant.now();

        // The amount is changed after signing
        String signature = WebhookSignature.sign(SECRET, ts(now), body);
        mvc.perform(webhook(body.replace("\"amount\":10000,", "\"amount\":10000000,"), ts(now), signature))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_SIGNATURE"));
        // Signed with a guessed secret
        send(body, now, "a-guessed-secret-that-is-long-enough!!")
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_SIGNATURE"));
        // A genuine request captured 10 minutes ago and replayed now
        send(body, now.minus(Duration.ofMinutes(10)), SECRET)
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("STALE_TIMESTAMP"));
        // ...or dated in the future to stretch its lifetime
        send(body, now.plus(Duration.ofMinutes(10)), SECRET)
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("STALE_TIMESTAMP"));
        // No signature at all, even with a valid user token
        mvc.perform(post("/api/v1/webhooks/topups").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("SIGNATURE_MISSING"));

        assertThat(api.balance(owner, account)).isZero();
        assertThat(rejections() - auditedBefore).isEqualTo(5);
    }

    @Test
    void unknownAccountsAndMalformedPaymentsAreRefused() throws Exception {
        send(payload(eventId(), "70088800000000", 10_000), Instant.now(), SECRET)
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("UNKNOWN_VIRTUAL_ACCOUNT"));
        String fractional = "{\"eventId\":\"" + eventId() + "\",\"virtualAccount\":\"" + va
                + "\",\"amount\":1500.5,\"paidAt\":\"" + Instant.now() + "\"}";
        send(fractional, Instant.now(), SECRET)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PAYLOAD"));
        assertThat(api.balance(owner, account)).isZero();
    }

    @Test
    void tenSimultaneousDeliveriesOfOneEventCreditOnce() throws Exception {
        String body = payload(eventId(), va, 20_000);
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        try {
            for (int i = 0; i < 10; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    String response = send(body, Instant.now(), SECRET).andReturn().getResponse().getContentAsString();
                    return objectMapper.readTree(response).path("status").asText();
                }));
            }
            start.countDown();
            List<String> statuses = new ArrayList<>();
            for (Future<String> r : results) {
                statuses.add(r.get());
            }
            assertThat(statuses).filteredOn("CREDITED"::equals).hasSize(1);
            assertThat(statuses).filteredOn("DUPLICATE"::equals).hasSize(9);
        } finally {
            pool.shutdownNow();
        }
        assertThat(api.balance(owner, account)).isEqualTo(20_000);
    }

    // ---- helpers: what the partner bank would send ----

    static String payload(String eventId, String va, long amount) {
        return "{\"eventId\":\"" + eventId + "\",\"virtualAccount\":\"" + va + "\",\"amount\":" + amount
                + ",\"paidAt\":\"" + Instant.now() + "\"}";
    }

    private ResultActions send(String body, Instant timestamp, String secret) throws Exception {
        return mvc.perform(webhook(body, ts(timestamp), WebhookSignature.sign(secret, ts(timestamp), body)));
    }

    private static MockHttpServletRequestBuilder webhook(String body, String timestamp, String signature) {
        return post("/api/v1/webhooks/topups").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("X-Timestamp", timestamp).header("X-Signature", signature);
    }

    private static String ts(Instant instant) {
        return Long.toString(instant.getEpochSecond());
    }

    private static String eventId() {
        return "evt_" + UUID.randomUUID().toString().replace("-", "");
    }

    private int rejections() {
        return jdbc.queryForObject("select count(*) from audit_logs where action = 'WEBHOOK_REJECTED'", Integer.class);
    }
}
