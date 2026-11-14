package com.ticketflow.identity.auth;

import com.ticketflow.identity.tokens.IssuedToken;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /api/v1/auth/login (contracts/identity.openapi.yaml).
 * The refresh cookie that the contract also describes is added with the refresh endpoint, in the next pull request.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class LoginController {

    private final LoginService login;

    public LoginController(LoginService login) {
        this.login = login;
    }

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@RequestBody LoginRequest request) {
        IssuedToken token = login.login(request);
        return ResponseEntity.ok()
                // A response with a credential must never be stored by a cache (RFC 6749, section 5.1).
                .cacheControl(CacheControl.noStore())
                .header("Pragma", "no-cache")
                .body(new TokenResponse(token.value(), "Bearer", token.expiresInSeconds()));
    }
}
