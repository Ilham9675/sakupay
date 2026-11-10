package com.sakupay.user;

import com.sakupay.auth.CurrentUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/** The logged-in user's own profile and PIN. The user id always comes from the token, never from the request. */
@RestController
@RequestMapping("/api/v1/me")
@Tag(name = "Me", description = "The current user's profile and transaction PIN")
public class MeController {

    private final UserRepository users;
    private final PinService pinService;

    public MeController(UserRepository users, PinService pinService) {
        this.users = users;
        this.pinService = pinService;
    }

    @GetMapping
    @Operation(summary = "Current user's profile")
    public MeResponse me(@AuthenticationPrincipal Jwt jwt) {
        return MeResponse.of(users.findById(CurrentUser.id(jwt)).orElseThrow());
    }

    @PutMapping("/pin")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Set or change the 6-digit transaction PIN (requires the password)")
    public void setPin(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody SetPinRequest request) {
        pinService.setPin(CurrentUser.id(jwt), request.password(), request.pin());
    }

    @PostMapping("/pin/verify")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Check the PIN (e.g. before showing sensitive data); counts towards the lockout")
    public void verifyPin(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody VerifyPinRequest request) {
        pinService.verify(CurrentUser.id(jwt), request.pin());
    }

    public record SetPinRequest(@NotBlank String password,
                                @NotBlank @Pattern(regexp = "\\d{6}", message = "must be exactly 6 digits") String pin) {
        @Override
        public String toString() {
            return "SetPinRequest[****]";
        }
    }

    public record VerifyPinRequest(@NotBlank @Pattern(regexp = "\\d{6}", message = "must be exactly 6 digits") String pin) {
        @Override
        public String toString() {
            return "VerifyPinRequest[****]";
        }
    }

    public record MeResponse(long id, String fullName, String email, String phone, Role role, boolean pinSet,
                             Instant createdAt) {
        public static MeResponse of(User u) {
            return new MeResponse(u.getId(), u.getFullName(), u.getEmail(), u.getPhone(), u.getRole(), u.hasPin(),
                    u.getCreatedAt());
        }
    }
}
