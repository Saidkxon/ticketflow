package com.ticketflow.gateway;

import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.uri;
import static org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions.route;
import static org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions.http;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * The routing table (ADR-0013). Only paths listed here reach a service. Everything else,
 * including every /internal/** path, gets a 404 from the gateway. Payment and Notification
 * have no public routes at all. Limits on rate and body size are applied before routing (package limits).
 */
@Configuration(proxyBeanMethods = false)
class RoutesConfig {

    /**
     * Event reads go to the Catalog service (the contract is contracts/catalog.openapi.yaml), and so do the
     * organizer's writes: create, update, layout and publish. Reads are public except "my events"; the writes need a token
     * (BearerTokenFilter), and Catalog checks the token and the ORGANIZER role again.
     */
    @Bean
    RouterFunction<ServerResponse> catalogEventRoutes(
            @Value("${ticketflow.gateway.catalog-uri}") String catalogUri) {
        return route("catalog-events")
                .GET("/api/v1/events/**", http())
                .POST("/api/v1/events", http())
                .PUT("/api/v1/events/{eventId}", http())
                .PUT("/api/v1/events/{eventId}/layout", http())
                .POST("/api/v1/events/{eventId}/publish", http())
                .before(uri(catalogUri))
                .build();
    }

    /**
     * The signed-in user's own data and the Admin user management. The gateway checks that a valid token is
     * present (BearerTokenFilter); Identity checks the token again and decides about the Admin role.
     */
    @Bean
    RouterFunction<ServerResponse> identityUserRoutes(
            @Value("${ticketflow.gateway.identity-uri}") String identityUri) {
        return route("identity-users")
                .GET("/api/v1/users", http())
                .GET("/api/v1/users/me", http())
                .PUT("/api/v1/users/{userId}/roles", http())
                .before(uri(identityUri))
                .build();
    }

    /**
     * Location tracking (ADR-0015): the signed-in user's consent and positions, and the Admin reads of other
     * people's positions. The gateway checks that a valid token is present (BearerTokenFilter); Identity checks it
     * again and decides about the Admin role. Query strings (the history time range) pass through unchanged.
     */
    @Bean
    RouterFunction<ServerResponse> identityLocationRoutes(
            @Value("${ticketflow.gateway.identity-uri}") String identityUri) {
        return route("identity-location")
                .GET("/api/v1/location/consent", http())
                .PUT("/api/v1/location/consent", http())
                .POST("/api/v1/location/positions", http())
                .GET("/api/v1/admin/location/latest", http())
                .GET("/api/v1/admin/location/users/{userId}/history", http())
                .before(uri(identityUri))
                .build();
    }

    /**
     * Seat availability of an event: public, read by the seat map every few seconds (ADR-0011). It lives below
     * /api/v1/events, so it must be tried before the general events route, which sends everything else there to
     * Catalog. The order is what {@code @Order} sets: the lowest number is tried first.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    RouterFunction<ServerResponse> bookingAvailabilityRoute(
            @Value("${ticketflow.gateway.booking-uri}") String bookingUri) {
        return route("booking-availability")
                .GET("/api/v1/events/{eventId}/availability", http())
                .before(uri(bookingUri))
                .build();
    }

    /**
     * The signed-in user's bookings (ADR-0013). Every path needs a token (BearerTokenFilter, where the prefix is
     * listed); Booking checks it again. The hold paths below /api/v1/events come with the pull request that builds
     * them, and must be tried before the general events route, like the availability route above.
     */
    @Bean
    RouterFunction<ServerResponse> bookingRoutes(
            @Value("${ticketflow.gateway.booking-uri}") String bookingUri) {
        return route("booking")
                .GET("/api/v1/bookings", http())
                .GET("/api/v1/bookings/**", http())
                .POST("/api/v1/bookings", http())
                .POST("/api/v1/bookings/**", http())
                .before(uri(bookingUri))
                .build();
    }

    /**
     * Registration, login, refresh and logout go to the Identity service (contracts/identity.openapi.yaml).
     * The Cookie, Origin and X-TicketFlow-Client request headers and the Set-Cookie response header
     * pass through unchanged: the refresh cookie belongs to Identity. The public keys path is never routed.
     */
    @Bean
    RouterFunction<ServerResponse> identityAuthRoutes(
            @Value("${ticketflow.gateway.identity-uri}") String identityUri) {
        return route("identity-auth")
                .POST("/api/v1/auth/register", http())
                .POST("/api/v1/auth/login", http())
                .POST("/api/v1/auth/refresh", http())
                .POST("/api/v1/auth/logout", http())
                .before(uri(identityUri))
                .build();
    }
}
