package com.sakupay;

import tools.jackson.databind.ObjectMapper;
import com.sakupay.Api.Session;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Small budgets so the limits are reached quickly; each scenario uses its own client address or account. */
@SpringBootTest(properties = {
        "sakupay.rate-limit.login-per-ip.requests=10",
        "sakupay.rate-limit.login-per-email.requests=3",
        "sakupay.rate-limit.login-per-email.per=15m",
        "sakupay.rate-limit.transfers-per-user.requests=3"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class RateLimitTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    private Api api;

    @BeforeEach
    void setUp() {
        api = new Api(mvc, objectMapper);
    }

    /** A botnet guessing one victim's password from many addresses is stopped by the per-e-mail budget. */
    @Test
    void guessingOnePasswordFromManyAddressesIsThrottled() throws Exception {
        Session victim = api.newUser();   // its own login used 1 of the 3 attempts

        login(victim.email(), "Wrong123abc", "198.51.100.1").andExpect(status().isUnauthorized());
        login(victim.email(), "Wrong123abc", "198.51.100.2").andExpect(status().isUnauthorized());
        login(victim.email(), "Wrong123abc", "198.51.100.3")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(header().exists("Retry-After"));
        // Even the right password waits: the check happens before the password is looked at
        login(victim.email(), Api.PASSWORD, "198.51.100.4").andExpect(status().isTooManyRequests());
    }

    /** One address trying many accounts (credential stuffing) is stopped by the per-address budget. */
    @Test
    void oneAddressTryingManyAccountsIsThrottled() throws Exception {
        for (int i = 0; i < 10; i++) {
            login("someone" + i + "@test.local", "Wrong123abc", "203.0.113.9").andExpect(status().isUnauthorized());
        }
        login("someone-else@test.local", "Wrong123abc", "203.0.113.9").andExpect(status().isTooManyRequests());
        // Other clients are not affected
        login("someone-else@test.local", "Wrong123abc", "203.0.113.10").andExpect(status().isUnauthorized());
    }

    @Test
    void transfersHaveAPerUserBudget() throws Exception {
        Session alice = api.newUserWithPin();
        Session bob = api.newUserWithPin();
        String a = api.openAccount(alice);
        String b = api.openAccount(bob);
        api.deposit(a, 10_000);

        for (int i = 0; i < 3; i++) {
            api.transfer(alice, UUID.randomUUID().toString(), a, b, 100).andExpect(status().isCreated());
        }
        api.transfer(alice, UUID.randomUUID().toString(), a, b, 100)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        // Bob has his own budget
        api.transfer(bob, UUID.randomUUID().toString(), b, a, 100).andExpect(status().isCreated());
    }

    private ResultActions login(String email, String password, String clientIp) throws Exception {
        return mvc.perform(post("/api/v1/auth/login")
                .with(request -> {
                    request.setRemoteAddr(clientIp);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(java.util.Map.of("email", email, "password", password))));
    }
}
