package com.ticketflow.identity.sessions;

import com.ticketflow.identity.tokens.AccessTokenService;
import com.ticketflow.identity.users.User;
import com.ticketflow.identity.users.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sessions of signed-in users: opening one at login, rotating the refresh token, and ending it
 * (ADR-0004). One login is one "family" of refresh tokens. Each use of a token produces the next
 * one; using a token a second time is treated as theft and revokes the whole family.
 */
@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    private final RefreshTokenRepository tokens;
    private final UserRepository users;
    private final AccessTokenService accessTokens;
    private final RefreshProperties properties;
    private final Clock clock;

    SessionService(RefreshTokenRepository tokens, UserRepository users, AccessTokenService accessTokens,
            RefreshProperties properties, Clock clock) {
        this.tokens = tokens;
        this.users = users;
        this.accessTokens = accessTokens;
        this.properties = properties;
        this.clock = clock;
    }

    /** Starts a new family for a user who has just proven their password. */
    @Transactional
    public Session open(User user) {
        String refreshToken = store(user.getId(), UUID.randomUUID(), clock.instant());
        return new Session(accessTokens.issue(user), refreshToken);
    }

    /**
     * Exchanges a refresh token for a new access token and the next refresh token.
     *
     * <p>The revocation after a reuse must be saved even though the call then fails, so a rollback
     * is switched off for this exception.
     */
    @Transactional(noRollbackFor = InvalidRefreshTokenException.class)
    public Session refresh(String presentedToken) {
        if (presentedToken == null || presentedToken.isBlank()) {
            throw new InvalidRefreshTokenException();
        }
        Instant now = clock.instant();
        RefreshToken current = tokens.findByTokenHash(RefreshTokens.hash(presentedToken))
                .orElseThrow(InvalidRefreshTokenException::new);

        if (current.isRevoked()) {
            throw new InvalidRefreshTokenException();
        }
        if (current.isUsed()) {
            // The same token twice: either a thief or the real user holds an old copy. Both must log in again.
            tokens.revokeFamily(current.getFamilyId(), now);
            log.warn("refresh token reuse detected, family revoked familyId={} userId={}",
                    current.getFamilyId(), current.getUserId());
            throw new InvalidRefreshTokenException();
        }
        if (!current.getExpiresAt().isAfter(now)) {
            throw new InvalidRefreshTokenException();
        }
        Optional<User> user = users.findById(current.getUserId());
        if (user.isEmpty()) {
            throw new InvalidRefreshTokenException();
        }

        current.markUsed(now);
        String next = store(current.getUserId(), current.getFamilyId(), now);
        // The roles are read again here, so a role change reaches the token at the next refresh (ADR-0004).
        return new Session(accessTokens.issue(user.get()), next);
    }

    /**
     * Logout: revokes the whole family of the presented token. Never fails, so a client may call it
     * at any time; an unknown or missing token simply does nothing.
     */
    @Transactional
    public void end(String presentedToken) {
        if (presentedToken == null || presentedToken.isBlank()) {
            return;
        }
        tokens.findByTokenHash(RefreshTokens.hash(presentedToken))
                .ifPresent(token -> tokens.revokeFamily(token.getFamilyId(), clock.instant()));
    }

    private String store(UUID userId, UUID familyId, Instant now) {
        String value = RefreshTokens.newToken();
        tokens.save(new RefreshToken(userId, familyId, RefreshTokens.hash(value), now, now.plus(properties.ttl())));
        return value;
    }
}
