package com.sakupay.auth;

import org.springframework.security.oauth2.jwt.Jwt;

/** The authenticated user's id, taken from the {@code sub} claim of a verified access token. */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static long id(Jwt jwt) {
        return Long.parseLong(jwt.getSubject());
    }
}
