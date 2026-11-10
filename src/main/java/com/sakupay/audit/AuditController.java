package com.sakupay.audit;

import com.sakupay.common.PageResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/** Read-only view of the audit trail. Only AUDITOR may call it (see SecurityConfig): admins cannot audit themselves. */
@RestController
@RequestMapping("/api/v1/audit-logs")
@Tag(name = "Audit", description = "Read-only audit trail (AUDITOR role)")
public class AuditController {

    private final AuditLogRepository repository;

    public AuditController(AuditLogRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    @Transactional(readOnly = true)
    @Operation(summary = "Search the audit trail, newest first")
    public PageResponse<AuditLogView> search(@RequestParam(required = false) String actor,
                                             @RequestParam(required = false) AuditAction action,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "50") int size) {
        PageRequest request = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200));
        return PageResponse.of(repository.search(actor, action == null ? null : action.name(), request), AuditLogView::of);
    }

    public record AuditLogView(long id, String actor, String action, String target, String ipAddress,
                               String details, Instant createdAt) {
        static AuditLogView of(AuditLog a) {
            return new AuditLogView(a.getId(), a.getActor(), a.getAction(), a.getTarget(), a.getIpAddress(),
                    a.getDetails(), a.getCreatedAt());
        }
    }
}
