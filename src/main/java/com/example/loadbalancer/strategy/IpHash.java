package com.example.loadbalancer.strategy;

import com.example.loadbalancer.Backend;

import java.util.List;
import java.util.Set;

/**
 * Pins each client IP to one backend (sticky sessions). If that backend is down,
 * walks forward to the next healthy one.
 */
public final class IpHash implements Strategy {

    @Override
    public Backend next(List<Backend> backends, String clientIp, Set<Backend> exclude) {
        int n = backends.size();
        if (n == 0) return null;
        int start = Math.floorMod(fnv1a(clientIp == null ? "" : clientIp), n);
        for (int i = 0; i < n; i++) {
            Backend b = backends.get((start + i) % n);
            if (Strategy.usable(b, exclude)) return b;
        }
        return null;
    }

    /** FNV-1a: a small, well-distributed hash (String.hashCode clusters similar IPs). */
    private static int fnv1a(String s) {
        int h = 0x811c9dc5;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x01000193;
        }
        return h;
    }
}
