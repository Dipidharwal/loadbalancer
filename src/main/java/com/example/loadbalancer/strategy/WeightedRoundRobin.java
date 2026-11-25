package com.example.loadbalancer.strategy;

import com.example.loadbalancer.Backend;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * nginx-style "smooth" weighted round-robin: a backend with weight 3 gets 3x the
 * traffic of a backend with weight 1, interleaved evenly rather than in bursts.
 */
public final class WeightedRoundRobin implements Strategy {

    private final Map<Backend, Integer> currentWeight = new IdentityHashMap<>();

    @Override
    public synchronized Backend next(List<Backend> backends, String clientIp, Set<Backend> exclude) {
        Backend best = null;
        int bestWeight = 0;
        int total = 0;
        for (Backend b : backends) {
            if (!Strategy.usable(b, exclude)) continue;
            int w = currentWeight.merge(b, b.weight(), Integer::sum);
            total += b.weight();
            if (best == null || w > bestWeight) {
                best = b;
                bestWeight = w;
            }
        }
        if (best != null) {
            currentWeight.merge(best, -total, Integer::sum);
        }
        return best;
    }
}
