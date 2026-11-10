package com.sakupay.audit;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Clock;
import java.time.Instant;

/**
 * Writes audit entries in the caller's transaction, so an action and its audit entry are committed together.
 * Callers that reject a request after recording a failure (wrong PIN, wrong password) use
 * {@code noRollbackFor = ApiException.class}, so the failure is still recorded.
 */
@Service
public class AuditService {

    public static final String SYSTEM = "system";
    public static final String ANONYMOUS = "anonymous";

    private final AuditLogRepository repository;
    private final Clock clock;

    public AuditService(AuditLogRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public static String user(long userId) {
        return "user:" + userId;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String actor, AuditAction action, String target, String details) {
        repository.save(new AuditLog(actor, action, target, clientIp(), details, Instant.now(clock)));
    }

    /**
     * The address of the TCP peer. Behind a reverse proxy, enable {@code server.forward-headers-strategy}
     * so this becomes the real client address; a raw X-Forwarded-For header is never trusted.
     */
    private static String clientIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            HttpServletRequest request = attributes.getRequest();
            return request.getRemoteAddr();
        }
        return null;
    }
}
