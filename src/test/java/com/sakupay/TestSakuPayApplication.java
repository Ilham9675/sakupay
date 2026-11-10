package com.sakupay;

import org.springframework.boot.SpringApplication;

/**
 * Runs SakuPay on a developer machine with a throwaway PostgreSQL 16, no installation needed:
 * Testcontainers if Docker is running, otherwise embedded PostgreSQL binaries (see TestcontainersConfiguration).
 *
 * <pre>
 * set -a; . ./.env.example; set +a
 * mvn spring-boot:test-run
 * </pre>
 *
 * The database starts empty on every run (the bootstrap admin is created again from ADMIN_EMAIL/ADMIN_PASSWORD).
 */
public class TestSakuPayApplication {

    public static void main(String[] args) {
        SpringApplication.from(SakuPayApplication::main)
                .with(TestcontainersConfiguration.class)
                .run(args);
    }
}
