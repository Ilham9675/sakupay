package com.sakupay.user;

import com.sakupay.audit.AuditAction;
import com.sakupay.audit.AuditService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;

/**
 * Creates the first ADMIN from {@code ADMIN_EMAIL} / {@code ADMIN_PASSWORD} when that user does not exist yet.
 * There is no other way to obtain the ADMIN role: registration always creates a USER.
 */
@Component
class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);
    private static final int MIN_ADMIN_PASSWORD = 12;

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final AuditService audit;
    private final Clock clock;
    private final String email;
    private final String password;

    AdminBootstrap(UserRepository users, PasswordEncoder encoder, AuditService audit, Clock clock,
                   @Value("${sakupay.bootstrap.admin-email:}") String email,
                   @Value("${sakupay.bootstrap.admin-password:}") String password) {
        this.users = users;
        this.encoder = encoder;
        this.audit = audit;
        this.clock = clock;
        this.email = email.strip().toLowerCase(Locale.ROOT);
        this.password = password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (email.isEmpty() || password.isEmpty() || users.existsByEmail(email)) {
            return;
        }
        if (password.length() < MIN_ADMIN_PASSWORD) {
            throw new IllegalStateException("ADMIN_PASSWORD must be at least " + MIN_ADMIN_PASSWORD + " characters.");
        }
        User admin = users.save(new User("Administrator", email, null, encoder.encode(password), Role.ADMIN,
                Instant.now(clock)));
        audit.record(AuditService.SYSTEM, AuditAction.ADMIN_BOOTSTRAPPED, "user:" + admin.getId(), null);
        log.info("Created the initial admin user {}", email);
    }
}
