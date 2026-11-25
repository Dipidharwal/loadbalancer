package com.example.loadbalancer.strategy;

import com.example.loadbalancer.Backend;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** Cycles through healthy backends in order. */
public final class RoundRobin implements Strategy {

    private final AtomicLong counter = new AtomicLong();

    @Override
    public Backend next(List<Backend> backends, String clientIp, Set<Backend> exclude) {
        int n = backends.size();
        if (n == 0) return null;
        long start = counter.getAndIncrement();
        for (int i = 0; i < n; i++) {
            Backend b = backends.get((int) Math.floorMod(start + i, (long) n));
            if (Strategy.usable(b, exclude)) return b;
        }
        return null;
    }
}
