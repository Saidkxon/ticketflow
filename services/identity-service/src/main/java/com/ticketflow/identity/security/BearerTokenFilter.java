package com.ticketflow.identity.security;

import com.ticketflow.identity.tokens.AccessTokenVerifier;
import com.ticketflow.identity.tokens.InvalidAccessTokenException;
import com.ticketflow.identity.tokens.VerifiedToken;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Turns a valid "Authorization: Bearer" access token into the signed-in user of this request:
 * the principal is the user ID (the token's sub) and the roles become ROLE_* authorities.
 * A missing or invalid token leaves the request anonymous; the access rules then answer 401
 * for protected paths, the same plain answer in every case (ADR-0004). The token is never logged.
 */
class BearerTokenFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(BearerTokenFilter.class);
    private static final String PREFIX = "Bearer ";

    private final AccessTokenVerifier verifier;

    BearerTokenFilter(AccessTokenVerifier verifier) {
        this.verifier = verifier;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
            try {
                VerifiedToken token = verifier.verify(header.substring(PREFIX.length()).trim());
                List<GrantedAuthority> authorities = token.roles().stream()
                        .<GrantedAuthority>map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
                        .toList();
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(
                        new UsernamePasswordAuthenticationToken(token.userId().toString(), null, authorities));
                SecurityContextHolder.setContext(context);
            } catch (InvalidAccessTokenException e) {
                // Security event (ADR-0004): no token, no reason in the log line.
                log.warn("access token rejected");
            }
        }
        chain.doFilter(request, response);
    }
}
