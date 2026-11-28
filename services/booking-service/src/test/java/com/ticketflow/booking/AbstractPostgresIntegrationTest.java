package com.ticketflow.booking;

import com.nimbusds.jose.jwk.RSAKey;
import com.sun.net.httpserver.HttpServer;
import com.ticketflow.booking.security.TokenFixtures;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/**
 * Base class for tests that start the application: they run against real PostgreSQL in a
 * throwaway container, never an in-memory database (ADR-0005). One container is started once
 * and shared by all test classes. A RabbitMQ container is shared the same way, because Booking consumes events.
 * Keep the image tags equal to deploy/compose.yaml.
 *
 * <p>A small stand-in for Identity serves the public keys, so tests can make real signed access tokens
 * with {@link #tokenFor}, and a stand-in for Catalog serves layouts (see {@link CatalogStandIn}).
 */
@SpringBootTest
public abstract class AbstractPostgresIntegrationTest {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");
    private static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.3.8-management");
    private static final RSAKey KEY = TokenFixtures.newKey("test-key");
    private static final HttpServer IDENTITY = startIdentityStandIn();

    static {
        POSTGRES.start();
        RABBIT.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
        // Short waits, so the tests that fail on purpose finish in seconds.
        registry.add("ticketflow.booking.provisioning.catalog-uri", CatalogStandIn::uri);
        registry.add("ticketflow.booking.provisioning.max-attempts", () -> "3");
        // No availability cache in the integration tests, so they see every change at once; the cache has its own tests.
        registry.add("ticketflow.booking.availability.cache-ttl", () -> "0s");
        registry.add("ticketflow.booking.provisioning.retry-delay", () -> "1s");
        registry.add("ticketflow.booking.payment-events.max-attempts", () -> "3");
        registry.add("ticketflow.booking.payment-events.retry-delay", () -> "1s");
        // The outbox relay and the expiry sweeper do not run by themselves in tests: a test that needs one calls it.
        registry.add("ticketflow.booking.outbox.schedule-enabled", () -> "false");
        registry.add("ticketflow.booking.expiry.schedule-enabled", () -> "false");
        registry.add("ticketflow.booking.jwt.jwks-uri",
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
