package com.ticketflow.identity.auth;

import com.ticketflow.identity.tokens.AccessTokenService;
import com.ticketflow.identity.tokens.IssuedToken;
import com.ticketflow.identity.users.User;
import com.ticketflow.identity.users.UserRepository;
import com.ticketflow.identity.web.FieldProblem;
import com.ticketflow.identity.web.InvalidRequestException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Checks an email and password and issues an access token (FR-1). A wrong password and an unknown
 * email give exactly the same answer, and cost about the same time, because a password is always
 * hashed and compared (ADR-0004).
 */
@Service
public class LoginService {

    private static final Logger log = LoggerFactory.getLogger(LoginService.class);

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final LoginThrottle throttle;
    private final AccessTokenService tokens;
    /** Compared against when the email is unknown, so that case takes as long as a real check. */
    private final String unknownUserHash;

    public LoginService(UserRepository users, PasswordEncoder passwordEncoder, LoginThrottle throttle,
            AccessTokenService tokens) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.throttle = throttle;
        this.tokens = tokens;
        this.unknownUserHash = passwordEncoder.encode("a password that no account has");
    }

    public IssuedToken login(LoginRequest request) {
        validate(request);
        String email = request.email().trim().toLowerCase(Locale.ROOT);

        throttle.checkAllowed(email);

        Optional<User> user = users.findByEmail(email);
        String hash = user.map(User::getPasswordHash).orElse(unknownUserHash);
        boolean passwordMatches = passwordEncoder.matches(request.password(), hash);

        if (user.isEmpty() || !passwordMatches) {
            throttle.recordFailure(email);
            // Security event (ADR-0004): the correlation ID is in every log line; no email, no password.
            log.warn("login failed");
            throw new InvalidCredentialsException();
        }
        throttle.recordSuccess(email);
        log.info("login succeeded userId={}", user.get().getId());
        return tokens.issue(user.get());
    }

    private static void validate(LoginRequest request) {
        List<FieldProblem> problems = new ArrayList<>();
        if (request.email() == null || request.email().isBlank()) {
            problems.add(new FieldProblem("email", "is required"));
        } else if (request.email().length() > RegistrationService.MAX_EMAIL_LENGTH) {
            problems.add(new FieldProblem("email", "must be at most "
                    + RegistrationService.MAX_EMAIL_LENGTH + " characters"));
        }
        if (request.password() == null || request.password().isEmpty()) {
            problems.add(new FieldProblem("password", "is required"));
        } else if (request.password().length() > RegistrationService.MAX_PASSWORD_LENGTH) {
            // Never hash an oversized input (it costs CPU and no account can have such a password).
            problems.add(new FieldProblem("password", "must be at most "
                    + RegistrationService.MAX_PASSWORD_LENGTH + " characters"));
        }
        if (!problems.isEmpty()) {
            throw new InvalidRequestException(problems);
        }
    }
}
