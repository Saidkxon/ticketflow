package com.ticketflow.identity.auth;

import com.ticketflow.identity.sessions.RefreshCookies;
import com.ticketflow.identity.sessions.Session;
import org.springframework.http.HttpHeaders;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** POST /api/v1/auth/login (contracts/identity.openapi.yaml). Answers with an access token and sets the refresh cookie. */
@RestController
@RequestMapping("/api/v1/auth")
public class LoginController {

    private final LoginService login;
    private final RefreshCookies cookies;

    public LoginController(LoginService login, RefreshCookies cookies) {
        this.login = login;
        this.cookies = cookies;
    }

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@RequestBody LoginRequest request) {
        Session session = login.login(request);
        return ResponseEntity.ok()
                // A response with a credential must never be stored by a cache (RFC 6749, section 5.1).
                .cacheControl(CacheControl.noStore())
                .header("Pragma", "no-cache")
                .header(HttpHeaders.SET_COOKIE, cookies.set(session.refreshToken()))
                .body(new TokenResponse(session.accessToken().value(), "Bearer",
                        session.accessToken().expiresInSeconds()));
    }
}
