package com.ticketflow.catalog;

import com.nimbusds.jose.jwk.RSAKey;
import com.sun.net.httpserver.HttpServer;
import com.ticketflow.catalog.security.TokenFixtures;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base class for tests that start the application: they run against real PostgreSQL in a
 * throwaway container, never an in-memory database (ADR-0005). One container is started once
 * and shared by all test classes. Keep the image tag equal to deploy/compose.yaml.
 *
 * <p>A small stand-in for Identity serves the public keys, so tests can make real signed access tokens
 * with {@link #tokenFor}.
 */
@SpringBootTest
public abstract class AbstractPostgresIntegrationTest {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");
    private static final RSAKey KEY = TokenFixtures.newKey("test-key");
    private static final HttpServer IDENTITY = startIdentityStandIn();

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("ticketflow.catalog.jwt.jwks-uri",
                () -> "http://127.0.0.1:" + IDENTITY.getAddress().getPort() + "/.well-known/jwks.json");
    }

    /** A valid access token for this user with exactly these roles. */
    protected static String tokenFor(UUID userId, String... roles) {
        return TokenFixtures.token(KEY, userId, roles);
    }

    private static HttpServer startIdentityStandIn() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            byte[] keys = TokenFixtures.jwks(KEY).getBytes(StandardCharsets.UTF_8);
            server.createContext("/.well-known/jwks.json", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, keys.length);
                exchange.getResponseBody().write(keys);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
