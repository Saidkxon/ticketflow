package com.ticketflow.identity.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/** 401 for a missing or invalid credential: code UNAUTHENTICATED, with no hint about the reason. */
@Component
class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException authException) throws IOException {
        ProblemResponses.write(response, 401, "UNAUTHENTICATED", "Unauthenticated",
                "Authentication is required.");
    }
}
