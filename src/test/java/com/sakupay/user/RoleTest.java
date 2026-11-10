package com.sakupay.user;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sakupay.Api;
import com.sakupay.Api.Session;
import com.sakupay.IntegrationTest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IntegrationTest
class RoleTest {

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
    void usersCannotReachAdminOrAuditEndpoints() throws Exception {
        Session user = api.newUser();

        api.get("/api/v1/admin/users", user.accessToken())
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        api.put("/api/v1/admin/users/" + user.userId() + "/role", user.accessToken(), Map.of("role", "ADMIN"))
                .andExpect(status().isForbidden());
        api.get("/api/v1/audit-logs", user.accessToken()).andExpect(status().isForbidden());
    }

    @Test
    void adminsManageUsersButCannotReadTheAuditTrail() throws Exception {
        Session admin = api.admin();
        long adminId = api.body(api.get("/api/v1/me", admin.accessToken())).get("id").asLong();

        api.get("/api/v1/admin/users", admin.accessToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").isNumber());
        api.get("/api/v1/audit-logs", admin.accessToken()).andExpect(status().isForbidden());
        api.put("/api/v1/admin/users/" + adminId + "/role", admin.accessToken(), Map.of("role", "USER"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CANNOT_CHANGE_OWN_ROLE"));
        api.put("/api/v1/admin/users/999999999/role", admin.accessToken(), Map.of("role", "USER"))
                .andExpect(status().isNotFound());
    }

    @Test
    void anAuditorReadsTheTrailAndNothingElse() throws Exception {
        Session admin = api.admin();
        Session user = api.newUser();

        api.put("/api/v1/admin/users/" + user.userId() + "/role", admin.accessToken(), Map.of("role", "AUDITOR"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("AUDITOR"));
        // The old access token still carries USER until it expires or is refreshed
        api.get("/api/v1/audit-logs", user.accessToken()).andExpect(status().isForbidden());

        Session auditor = api.login(user.userId(), user.email(), Api.PASSWORD);
        JsonNode trail = api.body(api.get("/api/v1/audit-logs?action=ROLE_CHANGED", auditor.accessToken())
                .andExpect(status().isOk()));
        assertThat(trail.get("content")).anySatisfy(e -> {
            assertThat(e.get("target").asText()).isEqualTo("user:" + user.userId());
            assertThat(e.get("details").asText()).isEqualTo("USER->AUDITOR");
            assertThat(e.get("ipAddress").asText()).isNotBlank();
        });
        api.get("/api/v1/admin/users", auditor.accessToken()).andExpect(status().isForbidden());
    }
}
