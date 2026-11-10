package com.sakupay;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.io.IOException;

/**
 * Every integration test runs against a real PostgreSQL 16, never an in-memory imitation.
 * <ul>
 *   <li>With Docker (CI): Testcontainers starts the same image as docker-compose.yml.</li>
 *   <li>Without Docker (a laptop where Docker is off): embedded-postgres runs the PostgreSQL 16 binaries as a
 *       local process. CI checks that no test was skipped, so the Testcontainers path is always exercised there.</li>
 * </ul>
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    @Conditional(DockerAvailable.class)
    PostgreSQLContainer<?> postgres() {
        return new PostgreSQLContainer<>("postgres:16-alpine");
    }

    @Bean(destroyMethod = "close")
    @Conditional(DockerUnavailable.class)
    EmbeddedPostgres embeddedPostgres() throws IOException {
        return EmbeddedPostgres.builder().start();
    }

    @Bean
    @Conditional(DockerUnavailable.class)
    DataSource dataSource(EmbeddedPostgres postgres) {
        return postgres.getPostgresDatabase();
    }

    static class DockerAvailable implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return DockerClientFactory.instance().isDockerAvailable();
        }
    }

    static class DockerUnavailable implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return !DockerClientFactory.instance().isDockerAvailable();
        }
    }
}
