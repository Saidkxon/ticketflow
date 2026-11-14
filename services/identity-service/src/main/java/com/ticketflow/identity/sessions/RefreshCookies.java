package com.ticketflow.identity.sessions;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * The refresh token cookie (ADR-0004): HttpOnly so scripts cannot read it, Secure so it only
 * travels over HTTPS, SameSite=Strict so other sites cannot make the browser send it, and limited
 * to the auth path so it is not sent with ordinary API calls.
 */
@Component
public class RefreshCookies {

    public static final String NAME = "refresh_token";
    static final String PATH = "/api/v1/auth";

    private final RefreshProperties properties;

    RefreshCookies(RefreshProperties properties) {
        this.properties = properties;
    }

    /** The Set-Cookie header value that stores a refresh token. */
    public String set(String refreshToken) {
        return build(refreshToken, properties.ttl().getSeconds());
    }

    /** The Set-Cookie header value that makes the browser delete the cookie. */
    public String clear() {
        return build("", 0);
    }

    /** The refresh token the browser sent, or null. */
    public String read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (NAME.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private String build(String value, long maxAgeSeconds) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(properties.cookieSecure())
                .sameSite("Strict")
                .path(PATH)
                .maxAge(maxAgeSeconds)
                .build()
                .toString();
    }
}
