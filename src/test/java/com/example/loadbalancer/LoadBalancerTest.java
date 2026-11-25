package com.example.loadbalancer;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class LoadBalancerTest {

    private final List<HttpServer> servers = new ArrayList<>();
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @AfterEach
    void stopServers() {
        servers.forEach(s -> s.stop(0));
    }

    // ---------- helpers ----------

    private HttpServer startServer(HttpHandler handler) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        s.createContext("/", handler);
        s.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        s.start();
        servers.add(s);
        return s;
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(code, b.length == 0 ? -1 : b.length);
        if (b.length > 0) ex.getResponseBody().write(b);
        ex.close();
    }

    /** A backend that answers /health with "ok" and everything else with its name. */
    private String backend(String name) throws IOException {
        return url(startServer(ex -> reply(ex, 200, ex.getRequestURI().getPath().equals("/health") ? "ok" : name)));
    }

    /** A URL nothing is listening on. */
    private String deadBackend() throws IOException {
        try (java.net.ServerSocket s = new java.net.ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return "http://127.0.0.1:" + s.getLocalPort(); // closed on return -> connection refused
        }
    }

    private static String url(HttpServer s) {
        return "http://127.0.0.1:" + s.getAddress().getPort();
    }

    private record Lb(LoadBalancer lb, String url) {}

    private Lb startLb(String strategy, List<String> urls, List<Integer> weights) throws IOException {
        Config cfg = new Config();
        cfg.strategy = strategy;
        cfg.maxRetries = 2;
        for (int i = 0; i < urls.size(); i++) {
            cfg.backends.add(new Config.BackendConfig(urls.get(i), weights == null ? 1 : weights.get(i)));
        }
        cfg.validate();
        LoadBalancer lb = new LoadBalancer(cfg);
        HttpServer s = LoadBalancer.createServer(lb, new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        s.start();
        servers.add(s);
        return new Lb(lb, url(s));
    }

    private HttpResponse<String> get(String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private Map<String, Integer> distribution(String lbUrl, int requests) throws Exception {
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < requests; i++) counts.merge(get(lbUrl + "/").body(), 1, Integer::sum);
        return counts;
    }

    // ---------- tests ----------

    @Test
    void roundRobinDistributesEvenly() throws Exception {
        Lb lb = startLb("round_robin", List.of(backend("a"), backend("b"), backend("c")), null);
        assertEquals(Map.of("a", 10, "b", 10, "c", 10), distribution(lb.url(), 30));
    }

    @Test
    void weightedRoundRobinRespectsWeights() throws Exception {
        Lb lb = startLb("weighted_round_robin", List.of(backend("a"), backend("b")), List.of(3, 1));
        assertEquals(Map.of("a", 30, "b", 10), distribution(lb.url(), 40));
    }

    @Test
    void failsOverToHealthyBackendAndMarksDeadOneDown() throws Exception {
        Lb lb = startLb("round_robin", List.of(deadBackend(), backend("a")), null);
        for (int i = 0; i < 5; i++) {
            HttpResponse<String> r = get(lb.url() + "/");
            assertEquals(200, r.statusCode());
            assertEquals("a", r.body());
        }
        assertFalse(lb.lb().backends().get(0).isAlive(), "passive check should mark dead backend down");
    }

    @Test
    void allBackendsDownReturns502() throws Exception {
        Lb lb = startLb("round_robin", List.of(deadBackend()), null);
        assertEquals(502, get(lb.url() + "/").statusCode());
    }

    @Test
    void postWithBodyIsRetriedWhenBackendRefusesConnection() throws Exception {
        String echo = url(startServer(ex -> reply(ex, 200,
                "got:" + new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))));
        Lb lb = startLb("round_robin", List.of(deadBackend(), echo), null);

        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(lb.url() + "/submit"))
                .POST(HttpRequest.BodyPublishers.ofString("hello")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode());
        assertEquals("got:hello", r.body());
    }

    @Test
    void ipHashIsSticky() {
        var strategy = com.example.loadbalancer.strategy.Strategy.byName("ip_hash");
        List<Backend> pool = List.of(
                new Backend(URI.create("http://a"), 1),
                new Backend(URI.create("http://b"), 1),
                new Backend(URI.create("http://c"), 1));
        Backend first = strategy.next(pool, "203.0.113.7", java.util.Set.of());
        for (int i = 0; i < 10; i++) {
            assertSame(first, strategy.next(pool, "203.0.113.7", java.util.Set.of()));
        }
        // If the pinned backend goes down, the client moves to another one.
        first.setAlive(false);
        assertNotSame(first, strategy.next(pool, "203.0.113.7", java.util.Set.of()));
    }

    @Test
    void leastConnectionsAvoidsBusyBackend() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        String slow = url(startServer(ex -> {
            try { release.await(); } catch (InterruptedException ignored) {}
            reply(ex, 200, "slow");
        }));
        Lb lb = startLb("least_connections", List.of(slow, backend("fast")), null);

        // Occupy the slow backend with one in-flight request.
        CompletableFuture<?> pending = CompletableFuture.runAsync(() -> {
            try { get(lb.url() + "/"); } catch (Exception ignored) {}
        });
        long deadline = System.currentTimeMillis() + 2000;
        while (lb.lb().backends().get(0).activeConnections() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(1, lb.lb().backends().get(0).activeConnections());

        for (int i = 0; i < 5; i++) assertEquals("fast", get(lb.url() + "/").body());
        release.countDown();
        pending.join();
    }

    @Test
    void healthCheckerMarksBackendDownAndBackUp() throws Exception {
        AtomicBoolean healthy = new AtomicBoolean(true);
        String u = url(startServer(ex -> reply(ex, healthy.get() ? 200 : 503, "")));
        Lb lb = startLb("round_robin", List.of(u), null);
        try (HealthChecker hc = new HealthChecker(lb.lb().backends(), "/health",
                java.time.Duration.ofHours(1), java.time.Duration.ofSeconds(1))) {
            healthy.set(false);
            hc.checkAll();
            assertFalse(lb.lb().backends().get(0).isAlive());

            healthy.set(true);
            hc.checkAll();
            assertTrue(lb.lb().backends().get(0).isAlive());
        }
    }

    @Test
    void forwardsPathQueryAndForwardedHeaders() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        String u = url(startServer(ex -> {
            seen.set(ex.getRequestURI() + " xff=" + ex.getRequestHeaders().getFirst("X-Forwarded-For")
                    + " custom=" + ex.getRequestHeaders().getFirst("X-Custom"));
            reply(ex, 201, "created");
        }));
        Lb lb = startLb("round_robin", List.of(u), null);

        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(lb.url() + "/api/items?id=7&q=a%20b"))
                .header("X-Custom", "yes").build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(201, r.statusCode());
        assertEquals("created", r.body());
        assertEquals("/api/items?id=7&q=a%20b xff=127.0.0.1 custom=yes", seen.get());
    }

    @Test
    void statusEndpointReportsCounters() throws Exception {
        Lb lb = startLb("round_robin", List.of(backend("a")), null);
        get(lb.url() + "/");
        String body = get(lb.url() + "/lb/status").body();
        assertTrue(body.contains("\"total_requests\":1"), body);
        assertTrue(body.contains("\"alive\":true"), body);
    }

    @Test
    void durationParsing() {
        assertEquals(java.time.Duration.ofMillis(500), Config.parseDuration("500ms"));
        assertEquals(java.time.Duration.ofSeconds(5), Config.parseDuration("5s"));
        assertThrows(IllegalArgumentException.class, () -> Config.parseDuration("5 seconds"));
    }
}
