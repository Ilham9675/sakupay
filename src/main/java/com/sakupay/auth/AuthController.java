package com.sakupay.auth;

import com.sakupay.auth.AuthDtos.LoginRequest;
import com.sakupay.auth.AuthDtos.RefreshRequest;
import com.sakupay.auth.AuthDtos.RegisterRequest;
import com.sakupay.auth.TokenService.TokenPair;
import com.sakupay.user.MeController.MeResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Auth", description = "Registration, login and token refresh")
@SecurityRequirements
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Register a new user (role USER)")
    public MeResponse register(@Valid @RequestBody RegisterRequest request) {
        return MeResponse.of(authService.register(request));
    }

    @PostMapping("/login")
    @Operation(summary = "Log in and receive an access token (15 min) and a refresh token (7 days)")
    public TokenPair login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request.email(), request.password());
    }

    @PostMapping("/refresh")
    @Operation(summary = "Exchange a refresh token for a new pair; the old refresh token stops working")
    public TokenPair refresh(@Valid @RequestBody RefreshRequest request) {
        return authService.refresh(request.refreshToken());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Revoke a refresh token")
    public void logout(@Valid @RequestBody RefreshRequest request) {
        authService.logout(request.refreshToken());
    }
}
