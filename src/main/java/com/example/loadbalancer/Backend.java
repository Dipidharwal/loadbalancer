package com.example.loadbalancer;

import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** One upstream server the load balancer can forward traffic to. */
public final class Backend {

    private final URI uri;
    private final int weight;

    private final AtomicBoolean alive = new AtomicBoolean(true); // optimistic until first health check
    private final AtomicLong activeConnections = new AtomicLong();
    private final AtomicLong totalRequests = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();

    public Backend(URI uri, int weight) {
        this.uri = uri;
        this.weight = Math.max(1, weight);
    }

    public URI uri()             { return uri; }
    public int weight()          { return weight; }
    public boolean isAlive()     { return alive.get(); }
    public long activeConnections() { return activeConnections.get(); }

    /** Sets the alive flag and returns the previous value. */
    public boolean setAlive(boolean up) { return alive.getAndSet(up); }

    void beginRequest() {
        activeConnections.incrementAndGet();
        totalRequests.incrementAndGet();
    }

    void endRequest()    { activeConnections.decrementAndGet(); }
    void recordFailure() { failures.incrementAndGet(); }

    /** JSON-friendly snapshot for the /lb/status endpoint. */
    public record Status(String url, boolean alive, int weight,
                         long activeConnections, long totalRequests, long failures) {}

    public Status status() {
        return new Status(uri.toString(), isAlive(), weight,
                activeConnections.get(), totalRequests.get(), failures.get());
    }

    @Override
    public String toString() { return uri.toString(); }
}
