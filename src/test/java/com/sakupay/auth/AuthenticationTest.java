package com.sakupay.auth;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.sakupay.Api;
import com.sakupay.Api.Session;
import com.sakupay.IntegrationTest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.MockMvc;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IntegrationTest
class AuthenticationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtEncoder encoder;

    @Autowired
    private JdbcTemplate jdbc;

    private Api api;

    @BeforeEach
    void setUp() {
        api = new Api(mvc, objectMapper);
    }

    @Test
    void registrationNormalisesInputAndNeverReturnsSecrets() throws Exception {
        JsonNode user = api.body(api.post("/api/v1/auth/register", null, Map.of(
                        "fullName", "  Siti Rahma ", "email", " Siti.Rahma@Example.COM ",
                        "phone", "0813-9876-5432", "password", "Rahasia123", "role", "ADMIN"))
                .andExpect(status().isCreated()));

        assertThat(user.get("email").asText()).isEqualTo("siti.rahma@example.com");
        assertThat(user.get("phone").asText()).isEqualTo("+6281398765432");
        assertThat(user.get("fullName").asText()).isEqualTo("Siti Rahma");
        // A "role" in the request is ignored: registration always creates a USER
        assertThat(user.get("role").asText()).isEqualTo("USER");
        assertThat(user.toString()).doesNotContain("Rahasia123").doesNotContainIgnoringCase("hash");
        String hash = jdbc.queryForObject("select password_hash from users where email = 'siti.rahma@example.com'", String.class);
        assertThat(hash).startsWith("$2a$").doesNotContain("Rahasia123");
    }

    @Test
    void registrationRejectsWeakOrDuplicateInput() throws Exception {
        api.post("/api/v1/auth/register", null, Map.of("fullName", "A", "email", "weak@test.local",
                        "phone", "081211112222", "password", "onlyletters"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.password").exists());
        api.post("/api/v1/auth/register", null, Map.of("fullName", "A", "email", "phone@test.local",
                        "phone", "12345", "password", "Secret123abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PHONE"));

        Session existing = api.newUser();
        api.post("/api/v1/auth/register", null, Map.of("fullName", "B", "email", existing.email().toUpperCase(),
                        "phone", "081299998888", "password", "Secret123abc"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_TAKEN"));
    }

    @Test
    void wrongPasswordAndUnknownEmailLookExactlyTheSame() throws Exception {
        Session user = api.newUser();

        JsonNode wrongPassword = api.body(api.post("/api/v1/auth/login", null,
                Map.of("email", user.email(), "password", "Wrong123abc")).andExpect(status().isUnauthorized()));
        JsonNode unknownEmail = api.body(api.post("/api/v1/auth/login", null,
                Map.of("email", "nobody@test.local", "password", "Wrong123abc")).andExpect(status().isUnauthorized()));

        assertThat(wrongPassword).isEqualTo(unknownEmail);
        assertThat(wrongPassword.get("code").asText()).isEqualTo("INVALID_CREDENTIALS");
        // ...but the failure is in the audit trail, even though the request was rejected
        Integer failures = jdbc.queryForObject("select count(*) from audit_logs where action = 'LOGIN_FAILED' and actor = ?",
                Integer.class, "user:" + user.userId());
        assertThat(failures).isEqualTo(1);
    }

    @Test
    void protectedEndpointsRejectMissingForgedAndExpiredTokens() throws Exception {
        Session user = api.newUser();
        api.get("/api/v1/me", user.accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(user.email()));

        api.get("/api/v1/me", null)
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        api.get("/api/v1/me", "not-a-jwt").andExpect(status().isUnauthorized());

        Instant now = Instant.now();
        String subject = String.valueOf(user.userId());
        // Signed with someone else's key
        JwtEncoder attacker = new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(
                "an-attacker-secret-that-is-32-bytes!".getBytes(StandardCharsets.UTF_8), "HmacSHA256")));
        api.get("/api/v1/me", token(attacker, subject, "sakupay", now, now.plusSeconds(600)))
                .andExpect(status().isUnauthorized());
        // Our key, but expired
        api.get("/api/v1/me", token(encoder, subject, "sakupay", now.minus(Duration.ofHours(2)), now.minus(Duration.ofHours(1))))
                .andExpect(status().isUnauthorized());
        // Our key, but another issuer
        api.get("/api/v1/me", token(encoder, subject, "evil", now, now.plusSeconds(600)))
                .andExpect(status().isUnauthorized());
        // Unsigned ("alg": "none") with the real user id
        String header = b64("{\"alg\":\"none\"}");
        String payload = b64("{\"sub\":\"" + subject + "\",\"iss\":\"sakupay\",\"roles\":[\"ADMIN\"],\"exp\":"
                + now.plusSeconds(600).getEpochSecond() + "}");
        api.get("/api/v1/me", header + "." + payload + ".").andExpect(status().isUnauthorized());
    }

    @Test
    void refreshTokensRotateAndAReusedTokenEndsEverySession() throws Exception {
        Session user = api.newUser();
        String first = user.refreshToken();

        JsonNode rotated = api.body(api.post("/api/v1/auth/refresh", null, Map.of("refreshToken", first))
                .andExpect(status().isOk()));
        String second = rotated.get("refreshToken").asText();
        assertThat(second).isNotEqualTo(first);
        api.get("/api/v1/me", rotated.get("accessToken").asText()).andExpect(status().isOk());

        // The first token shows up again: it was stolen, or replayed
        api.post("/api/v1/auth/refresh", null, Map.of("refreshToken", first))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
        // So the legitimate newer token is revoked too: everyone has to log in again
        api.post("/api/v1/auth/refresh", null, Map.of("refreshToken", second))
                .andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForList("select details from audit_logs where action = 'REFRESH_TOKEN_REUSED' and actor = ?"
                + " order by id", String.class, "user:" + user.userId()))
                .first().isEqualTo("revokedSessions=1");
    }

    @Test
    void logoutRevokesTheRefreshTokenAndStoresOnlyHashes() throws Exception {
        Session user = api.newUser();

        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where token_hash = ?", Integer.class,
                user.refreshToken())).isZero();

        api.post("/api/v1/auth/logout", null, Map.of("refreshToken", user.refreshToken())).andExpect(status().isNoContent());
        api.post("/api/v1/auth/refresh", null, Map.of("refreshToken", user.refreshToken())).andExpect(status().isUnauthorized());
        // Logging out twice, or with garbage, reveals nothing
        api.post("/api/v1/auth/logout", null, Map.of("refreshToken", "garbage")).andExpect(status().isNoContent());
    }

    private static String token(JwtEncoder encoder, String subject, String issuer, Instant issuedAt, Instant expiresAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder().subject(subject).issuer(issuer).issuedAt(issuedAt)
                .expiresAt(expiresAt).claim("roles", List.of("USER")).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    private static String b64(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
