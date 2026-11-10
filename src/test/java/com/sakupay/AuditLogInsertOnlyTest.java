package com.sakupay;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The audit trail is enforced by the database: rows can be added, never changed or removed. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class AuditLogInsertOnlyTest {

    @Autowired
    private JdbcTemplate jdbc;

    private long id;

    @BeforeEach
    void insertEntry() {
        id = jdbc.queryForObject("""
                INSERT INTO audit_logs (actor, action, target, ip_address)
                VALUES ('alice', 'LOGIN', 'user:1', '127.0.0.1') RETURNING id
                """, Long.class);
    }

    @Test
    void entriesCanBeInsertedAndRead() {
        String action = jdbc.queryForObject("SELECT action FROM audit_logs WHERE id = ?", String.class, id);
        assertThat(action).isEqualTo("LOGIN");
    }

    @Test
    void entriesCannotBeUpdated() {
        assertThatThrownBy(() -> jdbc.update("UPDATE audit_logs SET actor = 'mallory' WHERE id = ?", id))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("insert-only");
        assertThat(jdbc.queryForObject("SELECT actor FROM audit_logs WHERE id = ?", String.class, id))
                .isEqualTo("alice");
    }

    @Test
    void entriesCannotBeDeletedOrTruncated() {
        assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_logs WHERE id = ?", id))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("insert-only");
        assertThatThrownBy(() -> jdbc.execute("TRUNCATE audit_logs"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("insert-only");
    }
}
