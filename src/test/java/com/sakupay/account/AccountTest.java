package com.sakupay.account;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sakupay.Api;
import com.sakupay.Api.Session;
import com.sakupay.IntegrationTest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IntegrationTest
class AccountTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    private Api api;

    @BeforeEach
    void setUp() {
        api = new Api(mvc, objectMapper);
    }

    @Test
    void anAccountNeedsAPinFirst() throws Exception {
        Session user = api.newUser();

        api.post("/api/v1/accounts", user.accessToken(), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PIN_NOT_SET"));
    }

    @Test
    void usersOpenUpToThreeEmptyRupiahAccounts() throws Exception {
        Session user = api.newUserWithPin();

        for (int i = 0; i < 3; i++) {
            JsonNode account = api.body(api.post("/api/v1/accounts", user.accessToken(), null)
                    .andExpect(status().isCreated()));
            String number = account.get("accountNumber").asText();
            assertThat(AccountNumbers.isValid(number)).as(number).isTrue();
            assertThat(number).startsWith("88");
            assertThat(account.get("balance").asLong()).isZero();
            assertThat(account.get("currency").asText()).isEqualTo("IDR");
            assertThat(account.get("status").asText()).isEqualTo("ACTIVE");
        }
        api.post("/api/v1/accounts", user.accessToken(), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LIMIT_REACHED"));
        api.get("/api/v1/accounts", user.accessToken()).andExpect(jsonPath("$.length()").value(3));
    }

    /** IDOR: knowing another user's account number must not be enough to read it. */
    @Test
    void anotherUsersAccountLooksExactlyLikeOneThatDoesNotExist() throws Exception {
        Session alice = api.newUserWithPin();
        Session bob = api.newUserWithPin();
        String alicesAccount = api.body(api.post("/api/v1/accounts", alice.accessToken(), null))
                .get("accountNumber").asText();

        api.get("/api/v1/accounts/" + alicesAccount, alice.accessToken()).andExpect(status().isOk());

        JsonNode someoneElses = api.body(api.get("/api/v1/accounts/" + alicesAccount, bob.accessToken())
                .andExpect(status().isNotFound()));
        JsonNode nonExistent = api.body(api.get("/api/v1/accounts/8800000000", bob.accessToken())
                .andExpect(status().isNotFound()));
        // Same status, code and message; only "instance" (the requested URL) differs
        ((com.fasterxml.jackson.databind.node.ObjectNode) someoneElses).remove("instance");
        ((com.fasterxml.jackson.databind.node.ObjectNode) nonExistent).remove("instance");
        assertThat(someoneElses).isEqualTo(nonExistent);
        assertThat(someoneElses.get("code").asText()).isEqualTo("ACCOUNT_NOT_FOUND");
        api.get("/api/v1/accounts", bob.accessToken()).andExpect(jsonPath("$.length()").value(0));
    }
}
