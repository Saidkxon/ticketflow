package com.ticketflow.payment;

import com.nimbusds.jose.jwk.RSAKey;
import com.sun.net.httpserver.HttpServer;
import com.ticketflow.payment.security.TokenFixtures;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/**
 * Base class for tests that start the application: they run against real PostgreSQL in a
 * throwaway container, never an in-memory database (ADR-0005). One container is started once
 * and shared by all test classes. A RabbitMQ container is shared the same way, because Payment publishes events.
 * Keep the image tags equal to deploy/compose.yaml.
 *
 * <p>A small stand-in for Identity serves the public keys, so tests can make real signed service tokens
 * with {@link #serviceToken}.
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
        // The outbox relay does not run by itself in tests: a test that needs it calls it.
        registry.add("ticketflow.payment.outbox.schedule-enabled", () -> "false");
        registry.add("ticketflow.payment.jwt.jwks-uri",
                () -> "http://127.0.0.1:" + IDENTITY.getAddress().getPort() + "/.well-known/jwks.json");
    }

    /** A valid service token from this caller for this service, with exactly these scopes. */
    protected static String serviceToken(String caller, String... scopes) {
        return TokenFixtures.serviceToken(KEY, caller, scopes);
    }

    /** A valid access token of a user (audience of the public API), which must never work on an internal endpoint. */
    protected static String userToken(java.util.UUID userId, String... roles) {
        return TokenFixtures.userToken(KEY, userId, roles);
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
