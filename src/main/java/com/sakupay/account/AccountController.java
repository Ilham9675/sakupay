package com.sakupay.account;

import com.sakupay.auth.CurrentUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1/accounts")
@Tag(name = "Accounts", description = "The current user's rupiah accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Open a new account (requires a PIN; at most 3 per user)")
    public AccountResponse open(@AuthenticationPrincipal Jwt jwt) {
        return AccountResponse.of(accountService.open(CurrentUser.id(jwt)));
    }

    @GetMapping
    @Operation(summary = "List my accounts")
    public List<AccountResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return accountService.list(CurrentUser.id(jwt)).stream().map(AccountResponse::of).toList();
    }

    @GetMapping("/{accountNumber}")
    @Operation(summary = "One of my accounts; other users' accounts are reported as not found")
    public AccountResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable String accountNumber) {
        return AccountResponse.of(accountService.get(CurrentUser.id(jwt), accountNumber));
    }

    public record AccountResponse(String accountNumber, String currency, long balance, long dailyTransferLimit,
                                  AccountStatus status, Instant createdAt) {
        static AccountResponse of(Account a) {
            return new AccountResponse(a.getAccountNumber(), a.getCurrency(), a.getBalance(),
                    a.getDailyTransferLimit(), a.getStatus(), a.getCreatedAt());
        }
    }
}
