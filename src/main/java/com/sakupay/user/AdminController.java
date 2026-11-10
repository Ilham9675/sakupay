package com.sakupay.user;

import com.sakupay.audit.AuditAction;
import com.sakupay.audit.AuditService;
import com.sakupay.auth.CurrentUser;
import com.sakupay.common.PageResponse;
import com.sakupay.common.error.ApiException;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;

/** User administration. Only ADMIN may call it (see SecurityConfig). */
@RestController
@RequestMapping("/api/v1/admin/users")
@Tag(name = "Admin", description = "User administration (ADMIN role)")
public class AdminController {

    private final UserRepository users;
    private final PinService pinService;
    private final AuditService audit;
    private final Clock clock;

    public AdminController(UserRepository users, PinService pinService, AuditService audit, Clock clock) {
        this.users = users;
        this.pinService = pinService;
        this.audit = audit;
        this.clock = clock;
    }

    @GetMapping
    @Operation(summary = "List users")
    public PageResponse<AdminUserView> list(@RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "50") int size) {
        PageRequest request = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200), Sort.by("id"));
        Instant now = Instant.now(clock);
        return PageResponse.of(users.findAll(request), u -> AdminUserView.of(u, now));
    }

    @PostMapping("/{id}/pin-unlock")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Unlock a user's PIN after too many wrong attempts")
    public void unlockPin(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        pinService.unlock(CurrentUser.id(jwt), id);
    }

    @PutMapping("/{id}/role")
    @Transactional
    @Operation(summary = "Change a user's role (takes effect on their next login or token refresh)")
    public AdminUserView changeRole(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                                    @Valid @RequestBody ChangeRoleRequest request) {
        long adminId = CurrentUser.id(jwt);
        if (adminId == id) {
            throw ApiException.conflict("CANNOT_CHANGE_OWN_ROLE", "Admins cannot change their own role.");
        }
        User user = users.findByIdForUpdate(id)
                .orElseThrow(() -> ApiException.notFound("USER_NOT_FOUND", "User not found."));
        Role previous = user.getRole();
        user.changeRole(request.role());
        audit.record(AuditService.user(adminId), AuditAction.ROLE_CHANGED, "user:" + id, previous + "->" + request.role());
        return AdminUserView.of(user, Instant.now(clock));
    }

    public record ChangeRoleRequest(@NotNull Role role) {
    }

    public record AdminUserView(long id, String fullName, String email, Role role, boolean pinLocked,
                                Instant createdAt) {
        static AdminUserView of(User u, Instant now) {
            return new AdminUserView(u.getId(), u.getFullName(), u.getEmail(), u.getRole(), u.isPinLocked(now),
                    u.getCreatedAt());
        }
    }
}
