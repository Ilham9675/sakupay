package com.sakupay.fraud;

import com.sakupay.auth.CurrentUser;
import com.sakupay.common.PageResponse;
import com.sakupay.fraud.FraudReviewService.AlertView;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Fraud", description = "Transfers held by fraud rules: ADMIN reviews, AUDITOR may read")
public class FraudAlertController {

    private final FraudReviewService reviews;

    public FraudAlertController(FraudReviewService reviews) {
        this.reviews = reviews;
    }

    @GetMapping("/api/v1/fraud-alerts")
    @Operation(summary = "Fraud alerts (OPEN first, oldest first; no status = all, newest first)")
    public PageResponse<AlertView> list(@RequestParam(required = false) FraudAlert.Status status,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "50") int size) {
        return reviews.list(status, page, size);
    }

    @PostMapping("/api/v1/admin/fraud-alerts/{id}/approve")
    @Operation(summary = "Release a held transfer to the receiver", tags = "Admin")
    public AlertView approve(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                             @Valid @RequestBody(required = false) ReviewRequest request) {
        return reviews.approve(CurrentUser.id(jwt), id, request == null ? null : request.note());
    }

    @PostMapping("/api/v1/admin/fraud-alerts/{id}/reject")
    @Operation(summary = "Return a held transfer to the sender (a note is required)", tags = "Admin")
    public AlertView reject(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                            @Valid @RequestBody(required = false) ReviewRequest request) {
        return reviews.reject(CurrentUser.id(jwt), id, request == null ? null : request.note());
    }

    public record ReviewRequest(@Size(max = 500) String note) {
    }
}
