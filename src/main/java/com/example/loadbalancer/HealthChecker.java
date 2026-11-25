package com.example.loadbalancer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/** Periodically probes every backend's health endpoint and flips its alive flag. */
public final class HealthChecker implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(HealthChecker.class.getName());

    private final List<Backend> backends;
    private final String path;
    private final Duration interval;
    private final Duration timeout;
    private final HttpClient client;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "health-checker");
        t.setDaemon(true);
        return t;
    });

    public HealthChecker(List<Backend> backends, String path, Duration interval, Duration timeout) {
        this.backends = backends;
        this.path = path;
        this.interval = interval;
        this.timeout = timeout;
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(timeout)
                .build();
    }

    public void start() {
        scheduler.scheduleWithFixedDelay(this::checkAll, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Probes all backends in parallel and waits for the results. */
    public void checkAll() {
        CompletableFuture<?>[] probes = backends.stream()
                .map(b -> probe(b).thenAccept(up -> {
                    boolean was = b.setAlive(up);
                    if (was != up) LOG.info("health: backend " + b + " is " + (up ? "UP" : "DOWN"));
                }))
                .toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(probes).join();
    }

    private CompletableFuture<Boolean> probe(Backend b) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(b.uri().toString().replaceAll("/+$", "") + path))
                .timeout(timeout)
                .GET()
                .build();
        return client.sendAsync(req, HttpResponse.BodyHandlers.discarding())
                .thenApply(r -> r.statusCode() >= 200 && r.statusCode() < 400)
                .exceptionally(e -> false);
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
