package com.sakupay.transfer;

import com.sakupay.auth.CurrentUser;
import com.sakupay.common.ratelimit.RateLimitProperties;
import com.sakupay.common.ratelimit.RateLimiter;
import com.sakupay.transfer.TransferDtos.DepositRequest;
import com.sakupay.transfer.TransferDtos.TransactionResponse;
import com.sakupay.transfer.TransferDtos.TransferRequest;
import com.sakupay.transfer.TransferService.Outcome;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Transfers", description = "Money movements; every request needs an Idempotency-Key header")
public class TransferController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String REPLAYED = "Idempotent-Replayed";

    private final TransferService transferService;
    private final RateLimiter rateLimiter;
    private final RateLimitProperties limits;

    public TransferController(TransferService transferService, RateLimiter rateLimiter, RateLimitProperties limits) {
        this.transferService = transferService;
        this.rateLimiter = rateLimiter;
        this.limits = limits;
    }

    @PostMapping("/api/v1/transfers")
    @Operation(summary = "Transfer between accounts (PIN, balance and daily limit are checked)")
    public ResponseEntity<TransactionResponse> transfer(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "A unique value per transfer, e.g. a UUID. Retrying with the same key never moves money twice.")
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @Valid @RequestBody TransferRequest request) {
        long userId = CurrentUser.id(jwt);
        rateLimiter.check("transfers-user", Long.toString(userId), limits.transfersPerUser());
        return created(transferService.transfer(userId, idempotencyKey, request));
    }

    @GetMapping("/api/v1/transfers/{reference}")
    @Operation(summary = "Current status of a transfer I made (e.g. ON_HOLD -> COMPLETED or REJECTED)")
    public TransactionResponse find(@AuthenticationPrincipal Jwt jwt, @PathVariable String reference) {
        return transferService.find(CurrentUser.id(jwt), reference);
    }

    @PostMapping("/api/v1/admin/accounts/{accountNumber}/deposits")
    @Operation(summary = "Record a cash deposit at a teller (ADMIN)", tags = "Admin")
    public ResponseEntity<TransactionResponse> deposit(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String accountNumber,
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @Valid @RequestBody DepositRequest request) {
        return created(transferService.deposit(CurrentUser.id(jwt), idempotencyKey, accountNumber, request.amount(),
                request.description()));
    }

    /** A replay answers exactly like the original (201 and the same body), plus a header that says so. */
    private static ResponseEntity<TransactionResponse> created(Outcome outcome) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CREATED);
        if (outcome.replayed()) {
            response.header(REPLAYED, "true");
        }
        return response.body(outcome.transaction());
    }
}
