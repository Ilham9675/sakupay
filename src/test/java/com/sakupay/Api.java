package com.sakupay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Small HTTP client for the tests: registers users, logs in and calls endpoints with a bearer token. */
public class Api {

    public static final String PASSWORD = "Secret123abc";
    public static final String PIN = "246810";
    public static final String ADMIN_EMAIL = "admin@test.local";
    public static final String ADMIN_PASSWORD = "test-admin-password-123";

    private static final AtomicLong SEQUENCE = new AtomicLong(System.nanoTime() % 10_000_000);

    private final MockMvc mvc;
    private final ObjectMapper json;

    public Api(MockMvc mvc, ObjectMapper json) {
        this.mvc = mvc;
        this.json = json;
    }

    public record Session(long userId, String email, String accessToken, String refreshToken) {
    }

    /** Registers a new user with a unique e-mail and phone, and logs in. */
    public Session newUser() throws Exception {
        long n = SEQUENCE.incrementAndGet();
        String email = "user" + n + "@test.local";
        JsonNode user = body(post("/api/v1/auth/register", null, Map.of(
                "fullName", "Test User " + n, "email", email,
                "phone", "0812" + String.format("%08d", n % 100_000_000), "password", PASSWORD))
                .andExpect(status().isCreated()));
        return login(user.get("id").asLong(), email, PASSWORD);
    }

    public Session newUserWithPin() throws Exception {
        Session s = newUser();
        put("/api/v1/me/pin", s.accessToken(), Map.of("password", PASSWORD, "pin", PIN))
                .andExpect(status().isNoContent());
        return s;
    }

    public Session admin() throws Exception {
        return login(0, ADMIN_EMAIL, ADMIN_PASSWORD);
    }

    public Session login(long userId, String email, String password) throws Exception {
        JsonNode tokens = body(post("/api/v1/auth/login", null, Map.of("email", email, "password", password))
                .andExpect(status().isOk()));
        return new Session(userId, email, tokens.get("accessToken").asText(), tokens.get("refreshToken").asText());
    }

    public ResultActions get(String url, String token) throws Exception {
        return mvc.perform(auth(MockMvcRequestBuilders.get(url), token));
    }

    public ResultActions post(String url, String token, Object body) throws Exception {
        return mvc.perform(withBody(auth(MockMvcRequestBuilders.post(url), token), body));
    }

    public ResultActions put(String url, String token, Object body) throws Exception {
        return mvc.perform(withBody(auth(MockMvcRequestBuilders.put(url), token), body));
    }

    public JsonNode body(ResultActions result) throws Exception {
        String content = result.andReturn().getResponse().getContentAsString();
        return content.isEmpty() ? json.nullNode() : json.readTree(content);
    }

    private static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request, String token) {
        return token == null ? request : request.header("Authorization", "Bearer " + token);
    }

    private MockHttpServletRequestBuilder withBody(MockHttpServletRequestBuilder request, Object body) throws Exception {
        return body == null ? request : request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
    }
}
