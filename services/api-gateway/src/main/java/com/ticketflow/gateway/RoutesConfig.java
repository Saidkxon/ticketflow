package com.ticketflow.gateway;

import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.uri;
import static org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions.route;
import static org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions.http;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * The routing table (ADR-0013). Only paths listed here reach a service. Everything else,
 * including every /internal/** path, gets a 404 from the gateway. Payment and Notification
 * have no public routes at all. Limits on rate and body size are applied before routing (package limits).
 */
@Configuration(proxyBeanMethods = false)
class RoutesConfig {

    /** Event reads go to the Catalog service (the contract is contracts/catalog.openapi.yaml). */
    @Bean
    RouterFunction<ServerResponse> catalogEventRoutes(
            @Value("${ticketflow.gateway.catalog-uri}") String catalogUri) {
        return route("catalog-events")
                .GET("/api/v1/events/**", http())
                .before(uri(catalogUri))
                .build();
    }

    /**
     * Registration, login, refresh and logout go to the Identity service (contracts/identity.openapi.yaml).
     * The Cookie, Origin and X-TicketFlow-Client request headers and the Set-Cookie response header
     * pass through unchanged: the refresh cookie belongs to Identity. Nothing else of Identity is routed yet.
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
