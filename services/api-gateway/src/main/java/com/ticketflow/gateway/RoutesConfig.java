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
 * have no public routes at all.
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
}
