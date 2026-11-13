package com.ticketflow.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** ADR-0013 test obligation 5 (second half): a service that is too slow gives 504 without internal names. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "management.server.port=-1",
                "spring.http.clients.read-timeout=500ms"
        })
class UpstreamTimeoutTest {

    private static final HttpServer SLOW_CATALOG = startSlowStandIn();

    @Value("${local.server.port}")
    private int port;

    @DynamicPropertySource
    static void catalogAddress(DynamicPropertyRegistry registry) {
        registry.add("ticketflow.gateway.catalog-uri",
                () -> "http://127.0.0.1:" + SLOW_CATALOG.getAddress().getPort());
    }

    @AfterAll
    static void stopStandIn() {
        SLOW_CATALOG.stop(0);
    }

    @Test
    void answersGatewayTimeoutWhenTheServiceIsTooSlow() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/events")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(504);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
                .contains("application/problem+json");
        assertThat(response.body())
                .contains("\"code\":\"UPSTREAM_TIMEOUT\"")
                .doesNotContain("127.0.0.1")
                .doesNotContain("Exception");
    }

    /** A stand-in that accepts the request and then takes far longer than the 500 ms read timeout. */
    private static HttpServer startSlowStandIn() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                try {
                    Thread.sleep(3000);
                    exchange.sendResponseHeaders(200, -1);
                } catch (InterruptedException | IOException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    exchange.close();
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
