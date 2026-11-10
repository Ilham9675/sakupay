package com.sakupay.statement;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.sakupay.Api;
import com.sakupay.Api.Session;
import com.sakupay.IntegrationTest;
import com.sakupay.topup.WebhookSignature;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IntegrationTest
class StatementTest {

    private static final String EVIL = "=HYPERLINK(\"http://evil.example\",\"Click\")";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    private Api api;
    private Session alice;
    private Session bob;
    private String a;
    private String b;
    private final LocalDate today = LocalDate.now(ZoneId.of("Asia/Jakarta"));

    /** Deposit 1.000.000, top-up 200.000, send 300.000 to Bob, receive 50.000 back: closing 950.000. */
    @BeforeEach
    void setUp() throws Exception {
        api = new Api(mvc, objectMapper);
        alice = api.newUserWithPin();
        bob = api.newUserWithPin();
        a = api.openAccount(alice);
        b = api.openAccount(bob);
        api.deposit(a, 1_000_000);
        topUp(a, 200_000);
        transfer(alice, a, b, 300_000, EVIL);
        transfer(bob, b, a, 50_000, "Kembalian");
    }

    @Test
    void theStatementShowsEveryMovementWithOpeningAndClosingBalance() throws Exception {
        JsonNode s = statement(alice, a, "?from=" + today + "&to=" + today);

        assertThat(s.get("openingBalance").asLong()).isZero();
        assertThat(s.get("closingBalance").asLong()).isEqualTo(950_000);
        assertThat(s.get("totalCredits").asLong()).isEqualTo(1_250_000);
        assertThat(s.get("totalDebits").asLong()).isEqualTo(300_000);
        assertThat(s.get("totalLines").asLong()).isEqualTo(4);

        JsonNode lines = s.get("lines");
        assertThat(lines).extracting(l -> l.get("type").asText()).containsExactly("DEPOSIT", "TOPUP", "TRANSFER", "TRANSFER");
        assertThat(lines).extracting(l -> l.get("direction").asText()).containsExactly("CREDIT", "CREDIT", "DEBIT", "CREDIT");
        assertThat(lines).extracting(l -> l.get("balanceAfter").asLong()).containsExactly(1_000_000L, 1_200_000L, 900_000L, 950_000L);
        // Internal accounts are not shown as a counterparty; customer accounts are
        assertThat(lines.get(0).get("counterparty").isNull()).isTrue();
        assertThat(lines.get(2).get("counterparty").asText()).isEqualTo(b);
        assertThat(lines.get(3).get("description").asText()).isEqualTo("Kembalian");
    }

    @Test
    void periodsAndPagesWork() throws Exception {
        JsonNode future = statement(alice, a, "?from=" + today.plusDays(1) + "&to=" + today.plusDays(2));
        assertThat(future.get("openingBalance").asLong()).isEqualTo(950_000);
        assertThat(future.get("closingBalance").asLong()).isEqualTo(950_000);
        assertThat(future.get("lines")).isEmpty();

        JsonNode page1 = statement(alice, a, "?size=3&page=1");
        assertThat(page1.get("totalLines").asLong()).isEqualTo(4);
        assertThat(page1.get("lines")).hasSize(1);
        assertThat(page1.get("lines").get(0).get("balanceAfter").asLong()).isEqualTo(950_000);

        api.get("/api/v1/accounts/" + a + "/statement?from=" + today + "&to=" + today.minusDays(1), alice.accessToken())
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PERIOD"));
        api.get("/api/v1/accounts/" + a + "/statement?from=" + today.minusDays(400) + "&to=" + today, alice.accessToken())
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PERIOD"));
    }

    @Test
    void nobodyElseCanReadOrDownloadTheStatement() throws Exception {
        for (String path : new String[]{"/statement", "/statement.csv", "/statement.pdf"}) {
            api.get("/api/v1/accounts/" + a + path, bob.accessToken())
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
        }
    }

    @Test
    void theCsvOpensSafelyInASpreadsheet() throws Exception {
        MvcResult result = api.get("/api/v1/accounts/" + a + "/statement.csv", alice.accessToken())
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/csv;charset=UTF-8"))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"sakupay-" + a + "-" + today.minusDays(29) + "-to-" + today + ".csv\""))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn();
        String csv = new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        String[] rows = csv.substring(1).split("\r\n");   // skip the byte-order mark

        assertThat(rows).hasSize(5);
        assertThat(rows[0]).isEqualTo("\"Date\",\"Reference\",\"Type\",\"Status\",\"Direction\",\"Amount\",\"Balance after\",\"Counterparty\",\"Description\"");
        // Bob could type a formula as a transfer description; in Alice's spreadsheet it must stay plain text
        assertThat(rows[3]).endsWith(",\"'=HYPERLINK(\"\"http://evil.example\"\",\"\"Click\"\")\"");
        assertThat(rows[3]).contains("\"COMPLETED\",\"DEBIT\",\"300000\",\"900000\",\"" + b + "\"");
    }

    @Test
    void thePdfIsARealPdf() throws Exception {
        byte[] pdf = api.get("/api/v1/accounts/" + a + "/statement.pdf", alice.accessToken())
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_PDF_VALUE))
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        assertThat(pdf.length).isGreaterThan(1_000);
        // Kept as a sample for a quick look: target/sample-statement.pdf
        java.nio.file.Files.write(java.nio.file.Path.of("target", "sample-statement.pdf"), pdf);
    }

    // ---- helpers ----

    private JsonNode statement(Session user, String account, String query) throws Exception {
        return api.body(api.get("/api/v1/accounts/" + account + "/statement" + query, user.accessToken())
                .andExpect(status().isOk()));
    }

    private void transfer(Session user, String from, String to, long amount, String description) throws Exception {
        mvc.perform(post("/api/v1/transfers").header("Authorization", "Bearer " + user.accessToken())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("sourceAccount", from, "destinationAccount", to,
                                "amount", amount, "description", description, "pin", Api.PIN))))
                .andExpect(status().isCreated());
    }

    private void topUp(String account, long amount) throws Exception {
        String body = "{\"eventId\":\"evt_" + UUID.randomUUID().toString().replace("-", "") + "\",\"virtualAccount\":\"7008"
                + account + "\",\"amount\":" + amount + ",\"paidAt\":\"" + Instant.now() + "\"}";
        String ts = Long.toString(Instant.now().getEpochSecond());
        mvc.perform(post("/api/v1/webhooks/topups").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Timestamp", ts)
                        .header("X-Signature", WebhookSignature.sign("test-only-webhook-secret-32-bytes-min", ts, body)))
                .andExpect(status().isOk());
    }
}
