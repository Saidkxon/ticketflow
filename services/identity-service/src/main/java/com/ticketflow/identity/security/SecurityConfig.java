package com.ticketflow.identity.security;

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

/**
 * Security rules of the Identity service (ADR-0004). Everything is closed unless listed here.
 * Public so far: registration. Login, the public keys and the token checks arrive in later pull requests.
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
            ProblemAccessDeniedHandler accessDeniedHandler) throws Exception {
        http
                // No sessions and no browser login pages: every request stands on its own.
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                // Spring's CSRF token is for session cookies. Here the refresh cookie is protected
                // by a required custom header and an origin check (ADR-0004), added with that endpoint.
                .csrf(AbstractHttpConfigurer::disable)
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/register").permitAll()
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
