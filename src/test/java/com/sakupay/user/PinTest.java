package com.sakupay.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sakupay.Api;
import com.sakupay.Api.Session;
import com.sakupay.IntegrationTest;
import com.sakupay.common.error.ApiException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IntegrationTest
class PinTest {

    private static final String WRONG = "135790";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PinService pinService;

    @Autowired
    private UserRepository users;

    private Api api;

    @BeforeEach
    void setUp() {
        api = new Api(mvc, objectMapper);
    }

    @Test
    void settingAPinNeedsThePasswordAndAStrongPin() throws Exception {
        Session user = api.newUser();

        api.put("/api/v1/me/pin", user.accessToken(), Map.of("password", "Wrong123abc", "pin", Api.PIN))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("WRONG_PASSWORD"));
        api.put("/api/v1/me/pin", user.accessToken(), Map.of("password", Api.PASSWORD, "pin", "123456"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("WEAK_PIN"));
        api.put("/api/v1/me/pin", user.accessToken(), Map.of("password", Api.PASSWORD, "pin", "12a456"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        api.get("/api/v1/me", user.accessToken()).andExpect(jsonPath("$.pinSet").value(false));

        api.put("/api/v1/me/pin", user.accessToken(), Map.of("password", Api.PASSWORD, "pin", Api.PIN))
                .andExpect(status().isNoContent());
        api.get("/api/v1/me", user.accessToken()).andExpect(jsonPath("$.pinSet").value(true));
        assertThat(users.findById(user.userId()).orElseThrow().getPinHash()).startsWith("$2a$").isNotEqualTo(Api.PIN);
    }

    @Test
    void aCorrectPinResetsTheCounter() throws Exception {
        Session user = api.newUserWithPin();

        for (int remaining = 4; remaining >= 1; remaining--) {
            verify(user, WRONG).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("WRONG_PIN"))
                    .andExpect(jsonPath("$.remainingAttempts").value(remaining));
        }
        verify(user, Api.PIN).andExpect(status().isNoContent());
        // The counter starts again: four more mistakes still do not lock
        for (int i = 0; i < 4; i++) {
            verify(user, WRONG).andExpect(status().isForbidden());
        }
    }

    @Test
    void fiveWrongPinsLockEvenTheCorrectPinUntilAnAdminUnlocks() throws Exception {
        Session user = api.newUserWithPin();

        for (int i = 0; i < 4; i++) {
            verify(user, WRONG).andExpect(status().isForbidden());
        }
        verify(user, WRONG).andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value("PIN_LOCKED"))
                .andExpect(jsonPath("$.lockedUntil").exists());
        verify(user, Api.PIN).andExpect(status().isLocked());

        api.post("/api/v1/admin/users/" + user.userId() + "/pin-unlock", api.admin().accessToken(), null)
                .andExpect(status().isNoContent());
        verify(user, Api.PIN).andExpect(status().isNoContent());
    }

    /**
     * An attacker fires 20 guesses at once, hoping several are checked before the counter is saved.
     * The row lock makes them queue: exactly 4 are answered "wrong PIN", then the PIN is locked.
     */
    @Test
    void parallelGuessesCannotGetPastTheLimit() throws Exception {
        long userId = api.newUserWithPin().userId();
        int guesses = 20;
        ExecutorService pool = Executors.newFixedThreadPool(guesses);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        try {
            for (int i = 0; i < guesses; i++) {
                Callable<String> guess = () -> {
                    start.await();
                    try {
                        pinService.verify(userId, WRONG);
                        return "OK";
                    } catch (ApiException e) {
                        return e.getCode();
                    }
                };
                results.add(pool.submit(guess));
            }
            start.countDown();
            List<String> codes = new ArrayList<>();
            for (Future<String> r : results) {
                codes.add(r.get());
            }

            assertThat(codes).filteredOn("WRONG_PIN"::equals).hasSize(4);
            assertThat(codes).filteredOn("PIN_LOCKED"::equals).hasSize(16);
            assertThat(users.findById(userId).orElseThrow().getPinFailedAttempts()).isEqualTo(5);
        } finally {
            pool.shutdownNow();
        }
    }

    private org.springframework.test.web.servlet.ResultActions verify(Session user, String pin) throws Exception {
        return api.post("/api/v1/me/pin/verify", user.accessToken(), Map.of("pin", pin));
    }
}
