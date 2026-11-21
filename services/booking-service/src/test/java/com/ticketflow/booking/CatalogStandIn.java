package com.ticketflow.booking;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A small stand-in for the Catalog service in tests: it serves GET /api/v1/events/{id}/layout from a map, can be told
 * to fail a number of times first (to test retries), and counts the requests. One server is shared by all test classes.
 */
public final class CatalogStandIn {

    private static final Map<UUID, String> LAYOUTS = new ConcurrentHashMap<>();
    private static final Map<UUID, AtomicInteger> FAILURES_LEFT = new ConcurrentHashMap<>();
    private static final Map<UUID, AtomicInteger> REQUESTS = new ConcurrentHashMap<>();
    private static final HttpServer SERVER = start();

    private CatalogStandIn() {
    }

    public static String uri() {
        return "http://127.0.0.1:" + SERVER.getAddress().getPort();
    }

    /** From now on, this event's layout is served with status 200. */
    public static void serve(UUID eventId, String layoutJson) {
        LAYOUTS.put(eventId, layoutJson);
    }

    /** The next {@code times} requests for this event answer 503; use a large number for "always". */
    public static void failNext(UUID eventId, int times) {
        FAILURES_LEFT.put(eventId, new AtomicInteger(times));
    }

    public static int requestsFor(UUID eventId) {
        AtomicInteger count = REQUESTS.get(eventId);
        return count == null ? 0 : count.get();
    }

    private static HttpServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/v1/events/", exchange -> {
                String[] parts = exchange.getRequestURI().getPath().split("/");
                UUID eventId = null;
                try {
                    eventId = UUID.fromString(parts[4]);
                } catch (RuntimeException ignored) {
                    // not an event ID: answered 404 below
                }
                int status = 404;
                byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
                if (eventId != null) {
                    REQUESTS.computeIfAbsent(eventId, id -> new AtomicInteger()).incrementAndGet();
                    AtomicInteger failures = FAILURES_LEFT.get(eventId);
                    if (failures != null && failures.getAndDecrement() > 0) {
                        status = 503;
                    } else if (LAYOUTS.containsKey(eventId)) {
                        status = 200;
                        body = LAYOUTS.get(eventId).getBytes(StandardCharsets.UTF_8);
                    }
                }
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
