package com.ticketflow.identity.security;

import com.ticketflow.identity.tokens.AccessTokenVerifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/**
 * Security rules of the Identity service (ADR-0004). Everything is closed unless listed here.
 * Public: registration, login, refresh, logout and the public keys. Everything else needs a valid access token.
 * Refresh and logout are open to the security chain because they stand on the refresh cookie, and are guarded separately (BrowserRequestGuard).
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

    /**
     * Health endpoints live on their own port, which the gateway never routes (ADR-0008, ADR-0013).
     * They carry no data worth protecting and the container health check calls them without a token.
     */
    @Bean
    @Order(1)
    SecurityFilterChain healthChain(HttpSecurity http) throws Exception {
        http.securityMatcher(request -> request.getRequestURI().startsWith("/actuator"))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(AbstractHttpConfigurer::disable);
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain apiChain(HttpSecurity http, ProblemAuthenticationEntryPoint entryPoint,
            ProblemAccessDeniedHandler accessDeniedHandler, AccessTokenVerifier verifier) throws Exception {
        http
                // Reads and checks "Authorization: Bearer ..." before the access rules below are applied.
                // The filter is created here, not as a bean, so it only runs inside this chain.
                .addFilterBefore(new BearerTokenFilter(verifier), AuthorizationFilter.class)
                // No sessions and no browser login pages: every request stands on its own.
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                // Spring's CSRF token is for session cookies. Here the refresh cookie is protected
                // by a required custom header and an origin check (ADR-0004), see BrowserRequestGuard.
                .csrf(AbstractHttpConfigurer::disable)
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login",
                                "/api/v1/auth/refresh", "/api/v1/auth/logout").permitAll()
                        // Public keys; the gateway does not route this path (ADR-0004).
                        .requestMatchers(HttpMethod.GET, "/.well-known/jwks.json").permitAll()
                        // Admin only, decided here and again by nothing else: a missing role is a plain 403.
                        // Everything under /users except /me is Admin-only, so a future path is closed by default.
                        .requestMatchers(HttpMethod.GET, "/api/v1/users/me").authenticated()
                        .requestMatchers("/api/v1/users", "/api/v1/users/**").hasRole("ADMIN")
                        // Location (ADR-0015): consent and positions belong to the signed-in user; every read of other
                        // people's positions is Admin-only, and everything new under /admin is closed by default.
                        .requestMatchers("/api/v1/location/**").authenticated()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated());
        return http.build();
    }

    /** Spring's delegating encoder: new hashes use bcrypt and carry a "{bcrypt}" prefix, so the algorithm can be upgraded later. */
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /**
     * Users are checked by our own login code, not through this interface. Declaring the bean
     * stops Spring Boot from creating a default "user" with a generated password at startup.
     */
    @Bean
    UserDetailsService noDefaultUser() {
        return username -> {
            throw new UsernameNotFoundException("Users are not looked up through this interface.");
        };
    }
}
