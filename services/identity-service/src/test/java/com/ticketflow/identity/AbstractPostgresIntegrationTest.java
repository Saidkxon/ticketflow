package com.ticketflow.identity;

import com.ticketflow.identity.tokens.TestKeys;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base class for tests that start the application: they run against real PostgreSQL in a
 * throwaway container, never an in-memory database (ADR-0005). One container is started once
 * and shared by all test classes. Keep the image tag equal to deploy/compose.yaml.
 * Access tokens are signed with a throwaway key made for the test run.
 */
@SpringBootTest
public abstract class AbstractPostgresIntegrationTest {

    protected static final String SIGNING_KEY_ID = "test-1";

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");
    private static final Path KEY_DIRECTORY;

    static {
        POSTGRES.start();
        try {
            KEY_DIRECTORY = Files.createTempDirectory("identity-test-keys");
            TestKeys.write(KEY_DIRECTORY, SIGNING_KEY_ID);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void testProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("ticketflow.identity.jwt.key-directory", KEY_DIRECTORY::toString);
        registry.add("ticketflow.identity.jwt.signing-key-id", () -> SIGNING_KEY_ID);
    }
}
