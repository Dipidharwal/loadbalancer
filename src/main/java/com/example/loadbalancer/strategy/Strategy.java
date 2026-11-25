package com.example.loadbalancer.strategy;

import com.example.loadbalancer.Backend;

import java.util.List;
import java.util.Set;

/** Picks a backend for a request. */
public interface Strategy {

    /**
     * @param backends the full pool
     * @param clientIp the client's IP address (used by sticky strategies)
     * @param exclude  backends already tried for this request, so retries go elsewhere
     * @return a healthy backend, or {@code null} if none is available
     */
    Backend next(List<Backend> backends, String clientIp, Set<Backend> exclude);

    static Strategy byName(String name) {
        return switch (name == null ? "round_robin" : name) {
            case "round_robin"          -> new RoundRobin();
            case "weighted_round_robin" -> new WeightedRoundRobin();
            case "least_connections"    -> new LeastConnections();
            case "ip_hash"              -> new IpHash();
            default -> throw new IllegalArgumentException("unknown strategy: " + name);
        };
    }

    static boolean usable(Backend b, Set<Backend> exclude) {
        return b.isAlive() && !exclude.contains(b);
    }
}
