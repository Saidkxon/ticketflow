package com.ticketflow.identity.auth;

import com.ticketflow.identity.users.Role;
import com.ticketflow.identity.users.User;
import com.ticketflow.identity.users.UserRepository;
import com.ticketflow.identity.users.UserResponse;
import com.ticketflow.identity.web.FieldProblem;
import com.ticketflow.identity.web.InvalidRequestException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creates Customer accounts (FR-1). Validation limits follow contracts/identity.openapi.yaml. */
@Service
public class RegistrationService {

    static final int MIN_PASSWORD_LENGTH = 10;
    static final int MAX_PASSWORD_LENGTH = 72;
    static final int MAX_PASSWORD_BYTES = 72;
    static final int MAX_EMAIL_LENGTH = 254;
    static final int MAX_DISPLAY_NAME_LENGTH = 80;

    /** A deliberately simple shape check: something@something.something, no spaces. */
    private static final Pattern EMAIL_SHAPE = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;

    public RegistrationService(UserRepository users, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public UserResponse register(RegisterRequest request) {
        validate(request);
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        if (users.existsByEmail(email)) {
            throw new EmailAlreadyRegisteredException();
        }
        User user = new User(UUID.randomUUID(), email, passwordEncoder.encode(request.password()),
                request.displayName().trim(), Set.of(Role.CUSTOMER));
        try {
            // Flush now so a duplicate that slipped past the check above (two requests at the
            // same moment) is caught here, by the unique index, and not later at commit.
            users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw new EmailAlreadyRegisteredException();
        }
        return UserResponse.from(user);
    }

    private static void validate(RegisterRequest request) {
        List<FieldProblem> problems = new ArrayList<>();

        String email = request.email() == null ? null : request.email().trim();
        if (email == null || email.isEmpty()) {
            problems.add(new FieldProblem("email", "is required"));
        } else if (email.length() > MAX_EMAIL_LENGTH || !EMAIL_SHAPE.matcher(email).matches()) {
            problems.add(new FieldProblem("email", "must be a valid email address of at most "
                    + MAX_EMAIL_LENGTH + " characters"));
        }

        String password = request.password();
        if (password == null || password.isEmpty()) {
            problems.add(new FieldProblem("password", "is required"));
        } else if (password.length() < MIN_PASSWORD_LENGTH || password.length() > MAX_PASSWORD_LENGTH) {
            problems.add(new FieldProblem("password", "must be " + MIN_PASSWORD_LENGTH + " to "
                    + MAX_PASSWORD_LENGTH + " characters"));
        } else if (password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            problems.add(new FieldProblem("password", "must be at most " + MAX_PASSWORD_BYTES
                    + " bytes in UTF-8"));
        }

        String displayName = request.displayName() == null ? null : request.displayName().trim();
        if (displayName == null || displayName.isEmpty()) {
            problems.add(new FieldProblem("displayName", "is required"));
        } else if (displayName.length() > MAX_DISPLAY_NAME_LENGTH) {
            problems.add(new FieldProblem("displayName", "must be at most "
                    + MAX_DISPLAY_NAME_LENGTH + " characters"));
        }

        if (!problems.isEmpty()) {
            throw new InvalidRequestException(problems);
        }
    }
}
