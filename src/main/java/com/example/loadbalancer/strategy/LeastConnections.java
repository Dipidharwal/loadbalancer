package com.example.loadbalancer.strategy;

import com.example.loadbalancer.Backend;

import java.util.List;
import java.util.Set;

/** Picks the healthy backend with the fewest in-flight requests. */
public final class LeastConnections implements Strategy {

    @Override
    public Backend next(List<Backend> backends, String clientIp, Set<Backend> exclude) {
        Backend best = null;
        for (Backend b : backends) {
            if (!Strategy.usable(b, exclude)) continue;
            if (best == null || b.activeConnections() < best.activeConnections()) {
                best = b;
            }
        }
        return best;
    }
}
