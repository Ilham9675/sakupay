package com.sakupay.auth;

import com.sakupay.audit.AuditAction;
import com.sakupay.audit.AuditService;
import com.sakupay.auth.AuthDtos.RegisterRequest;
import com.sakupay.auth.TokenService.TokenPair;
import com.sakupay.common.error.ApiException;
import com.sakupay.user.PhoneNumbers;
import com.sakupay.user.Role;
import com.sakupay.user.User;
import com.sakupay.user.UserRepository;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

@Service
public class AuthService {

    private static final String INVALID_CREDENTIALS = "Invalid email or password.";
    private static final String INVALID_REFRESH = "The refresh token is invalid or expired. Please log in again.";

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final TokenService tokens;
    private final PasswordEncoder passwordEncoder;
    private final AuditService audit;
    private final Clock clock;
    /** Compared against when the e-mail is unknown, so both cases take the same time (no user enumeration). */
    private final String dummyHash;

    AuthService(UserRepository users, RefreshTokenRepository refreshTokens, TokenService tokens,
                PasswordEncoder passwordEncoder, AuditService audit, Clock clock) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.tokens = tokens;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
        this.clock = clock;
        this.dummyHash = passwordEncoder.encode("not-a-real-password-1");
    }

    @Transactional
    public User register(RegisterRequest request) {
        String email = normalizeEmail(request.email());
        String phone = PhoneNumbers.normalize(request.phone());
        if (request.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            // BCrypt only uses the first 72 bytes; refuse instead of silently truncating
            throw ApiException.badRequest("PASSWORD_TOO_LONG", "The password must be at most 72 bytes.");
        }
        String emailName = email.substring(0, email.indexOf('@'));
        if (emailName.length() >= 4 && request.password().toLowerCase(Locale.ROOT).contains(emailName)) {
            throw ApiException.badRequest("WEAK_PASSWORD", "The password must not contain your e-mail name.");
        }
        if (users.existsByEmail(email)) {
            throw ApiException.conflict("EMAIL_TAKEN", "This e-mail is already registered.");
        }
        if (users.existsByPhone(phone)) {
            throw ApiException.conflict("PHONE_TAKEN", "This phone number is already registered.");
        }
        User user = users.save(new User(request.fullName().strip(), email, phone,
                passwordEncoder.encode(request.password()), Role.USER, Instant.now(clock)));
        audit.record(AuditService.user(user.getId()), AuditAction.USER_REGISTERED, "user:" + user.getId(), null);
        return user;
    }

    /** Failed attempts are audited and committed even though the request is rejected. */
    @Transactional(noRollbackFor = ApiException.class)
    public TokenPair login(String rawEmail, String password) {
        String email = normalizeEmail(rawEmail);
        Optional<User> user = users.findByEmail(email);
        boolean matches = passwordEncoder.matches(password, user.map(User::getPasswordHash).orElse(dummyHash));
        if (user.isEmpty() || !matches) {
            String actor = user.map(u -> AuditService.user(u.getId())).orElse(AuditService.ANONYMOUS);
            audit.record(actor, AuditAction.LOGIN_FAILED, null, "email=" + email);
            throw ApiException.unauthorized("INVALID_CREDENTIALS", INVALID_CREDENTIALS);
        }
        audit.record(AuditService.user(user.get().getId()), AuditAction.LOGIN_SUCCEEDED, null, null);
        return tokens.issue(user.get());
    }

    /**
     * Refresh token rotation: each refresh token works once. If a token that was already used shows up again,
     * someone else has a copy of it, so every session of that user is ended.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public TokenPair refresh(String rawToken) {
        Instant now = Instant.now(clock);
        RefreshToken token = refreshTokens.findByTokenHashForUpdate(TokenService.hash(rawToken))
                .orElseThrow(() -> ApiException.unauthorized("INVALID_REFRESH_TOKEN", INVALID_REFRESH));
        String actor = AuditService.user(token.getUserId());
        if (token.isRevoked()) {
            int revoked = refreshTokens.revokeAllForUser(token.getUserId(), now);
            audit.record(actor, AuditAction.REFRESH_TOKEN_REUSED, null, "revokedSessions=" + revoked);
            throw ApiException.unauthorized("INVALID_REFRESH_TOKEN", INVALID_REFRESH);
        }
        if (token.isExpired(now)) {
            throw ApiException.unauthorized("INVALID_REFRESH_TOKEN", INVALID_REFRESH);
        }
        token.revoke(now);
        User user = users.findById(token.getUserId())
                .orElseThrow(() -> ApiException.unauthorized("INVALID_REFRESH_TOKEN", INVALID_REFRESH));
        audit.record(actor, AuditAction.TOKEN_REFRESHED, null, null);
        return tokens.issue(user);
    }

    /** Ends one session. Unknown or already revoked tokens are ignored, so logout never reveals anything. */
    @Transactional
    public void logout(String rawToken) {
        refreshTokens.findByTokenHashForUpdate(TokenService.hash(rawToken))
                .filter(t -> !t.isRevoked())
                .ifPresent(t -> {
                    t.revoke(Instant.now(clock));
                    audit.record(AuditService.user(t.getUserId()), AuditAction.LOGGED_OUT, null, null);
                });
    }

    static String normalizeEmail(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }
}
