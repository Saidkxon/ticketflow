package com.ticketflow.identity.sessions;

import com.ticketflow.identity.auth.TokenResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** POST /api/v1/auth/refresh and /logout (contracts/identity.openapi.yaml). */
@RestController
@RequestMapping("/api/v1/auth")
public class SessionController {

    private final SessionService sessions;
    private final RefreshCookies cookies;
    private final BrowserRequestGuard guard;

    SessionController(SessionService sessions, RefreshCookies cookies, BrowserRequestGuard guard) {
        this.sessions = sessions;
        this.cookies = cookies;
        this.guard = guard;
    }

    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(HttpServletRequest request) {
        guard.check(request);
        Session session = sessions.refresh(cookies.read(request));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("Pragma", "no-cache")
                .header(HttpHeaders.SET_COOKIE, cookies.set(session.refreshToken()))
                .body(new TokenResponse(session.accessToken().value(), "Bearer",
                        session.accessToken().expiresInSeconds()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        guard.check(request);
        sessions.end(cookies.read(request));
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookies.clear())
                .build();
    }
}
