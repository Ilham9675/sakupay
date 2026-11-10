package com.sakupay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sakupay.Api.Session;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP-level hardening: security headers on every response and a closed CORS policy. */
@IntegrationTest
class HardeningTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void everyResponseCarriesSecurityHeaders() throws Exception {
        Session user = new Api(mvc, objectMapper).newUser();

        for (var request : new org.springframework.test.web.servlet.RequestBuilder[]{
                get("/api/v1/me").header("Authorization", "Bearer " + user.accessToken()),
                get("/api/v1/me"),               // a 401 too
                get("/actuator/health")}) {
            mvc.perform(request)
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                    .andExpect(header().string("X-Frame-Options", "DENY"))
                    .andExpect(header().string("Cache-Control", containsString("no-store")))
                    .andExpect(header().string("Content-Security-Policy", containsString("frame-ancestors 'none'")))
                    .andExpect(header().string("Referrer-Policy", "no-referrer"))
                    .andExpect(header().string("Permissions-Policy", containsString("camera=()")));
        }
    }

    @Test
    void onlyConfiguredOriginsMayCallTheApiFromABrowser() throws Exception {
        mvc.perform(options("/api/v1/transfers")
                        .header("Origin", "https://app.sakupay.test")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "Authorization, Idempotency-Key"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://app.sakupay.test"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));

        mvc.perform(options("/api/v1/transfers")
                        .header("Origin", "https://evil.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
}
