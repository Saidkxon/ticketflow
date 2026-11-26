package com.ticketflow.identity.tokens;

import com.ticketflow.identity.auth.TokenResponse;
import com.ticketflow.identity.web.FieldProblem;
import com.ticketflow.identity.web.InvalidRequestException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /internal/v1/service-tokens (contracts/identity.internal.openapi.yaml): a service proves who it is with its
 * secret and gets a short-lived token for one target service (ADR-0004, Part D). The gateway does not route this path,
 * so only services inside the container network can reach it. It is open to the security chain because the secret in
 * the body is the credential; every other path under /internal stays closed.
 */
@RestController
@RequestMapping("/internal/v1")
public class ServiceTokenController {

    private static final Logger log = LoggerFactory.getLogger(ServiceTokenController.class);

    /** A service name: lower case letters, digits and hyphens, for example {@code booking-service}. */
    private static final Pattern SERVICE_NAME = Pattern.compile("[a-z][a-z0-9-]{0,63}");
    /** A scope: {@code <service>.<thing>.<action>}, for example {@code payment.attempt.create}. */
    private static final Pattern SCOPE = Pattern.compile("[a-z][a-z0-9.-]{0,63}");
    private static final int MAX_SECRET_LENGTH = 200;
    private static final int MAX_SCOPES = 10;

    private final ServiceTokenService tokens;

    public ServiceTokenController(ServiceTokenService tokens) {
        this.tokens = tokens;
    }

    @PostMapping("/service-tokens")
    public ResponseEntity<TokenResponse> issue(@RequestBody ServiceTokenRequest request) {
        Set<String> scopes = validate(request);
        IssuedToken token;
        try {
            token = tokens.issue(request.clientId(), request.clientSecret(), request.audience(), scopes);
        } catch (InvalidClientException e) {
            // Security event (ADR-0004): the name was checked against a strict pattern, the secret is never logged.
            log.warn("service token refused: unknown service or wrong secret client={}", request.clientId());
            throw e;
        } catch (ScopeNotAllowedException e) {
            log.warn("service token refused: not allowed client={} audience={} scopes={}",
                    request.clientId(), request.audience(), scopes);
            throw e;
        }
        log.info("service token issued client={} audience={}", request.clientId(), request.audience());
        return ResponseEntity.ok()
                // A response with a credential must never be stored by a cache (RFC 6749, section 5.1).
                .cacheControl(CacheControl.noStore())
                .header("Pragma", "no-cache")
                .body(new TokenResponse(token.value(), "Bearer", token.expiresInSeconds()));
    }

    private static Set<String> validate(ServiceTokenRequest request) {
        List<FieldProblem> problems = new ArrayList<>();
        if (request.clientId() == null || !SERVICE_NAME.matcher(request.clientId()).matches()) {
            problems.add(new FieldProblem("clientId", "must be a service name such as booking-service"));
        }
        if (request.clientSecret() == null || request.clientSecret().isEmpty()) {
            problems.add(new FieldProblem("clientSecret", "is required"));
        } else if (request.clientSecret().length() > MAX_SECRET_LENGTH) {
            problems.add(new FieldProblem("clientSecret", "must be at most " + MAX_SECRET_LENGTH + " characters"));
        }
        if (request.audience() == null || !SERVICE_NAME.matcher(request.audience()).matches()) {
            problems.add(new FieldProblem("audience", "must be a service name such as payment-service"));
        }
        Set<String> scopes = new LinkedHashSet<>();
        if (request.scopes() == null || request.scopes().isEmpty()) {
            problems.add(new FieldProblem("scopes", "must list at least one scope"));
        } else if (request.scopes().size() > MAX_SCOPES) {
            problems.add(new FieldProblem("scopes", "must list at most " + MAX_SCOPES + " scopes"));
        } else {
            for (String scope : request.scopes()) {
                if (scope == null || !SCOPE.matcher(scope).matches()) {
                    problems.add(new FieldProblem("scopes", "each scope must look like payment.attempt.create"));
                    break;
                }
                scopes.add(scope);
            }
        }
        if (!problems.isEmpty()) {
            throw new InvalidRequestException(problems);
        }
        return scopes;
    }
}
