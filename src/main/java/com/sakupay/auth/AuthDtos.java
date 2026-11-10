package com.sakupay.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Request bodies of the auth endpoints. */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record RegisterRequest(
            @NotBlank @Size(max = 100) String fullName,
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank String phone,
            @NotBlank @Size(min = 8, max = 72, message = "must be 8 to 72 characters")
            @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$", message = "must contain a letter and a digit")
            String password) {

        /** Surrounding spaces (copy-paste) are trimmed before validation. */
        public RegisterRequest {
            email = email == null ? null : email.strip();
            phone = phone == null ? null : phone.strip();
        }

        @Override
        public String toString() {
            return "RegisterRequest[email=" + email + ", password=****]";
        }
    }

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {

        @Override
        public String toString() {
            return "LoginRequest[email=" + email + ", password=****]";
        }
    }

    public record RefreshRequest(@NotBlank String refreshToken) {

        @Override
        public String toString() {
            return "RefreshRequest[refreshToken=****]";
        }
    }
}
