package com.example.loadbalancer;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Load balancer configuration, read from JSON. Durations are strings like "5s" or "500ms". */
public final class Config {

    public String listen = ":8080";
    public String strategy = "round_robin";
    public int maxRetries = 0;
    public String backendTimeout = "30s";
    public String healthCheckPath = "/health";
    public String healthCheckInterval = "5s";
    public String healthCheckTimeout = "2s";
    public long maxBodyBytes = 10 * 1024 * 1024;
    public List<BackendConfig> backends = new ArrayList<>();

    public static final class BackendConfig {
        public String url;
        public int weight = 1;

        public BackendConfig() {}
        public BackendConfig(String url, int weight) { this.url = url; this.weight = weight; }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES); // catch typos in config keys

    public static Config load(Path path) throws IOException {
        Config cfg = MAPPER.readValue(path.toFile(), Config.class);
        cfg.validate();
        return cfg;
    }

    public void validate() {
        if (backends == null || backends.isEmpty()) {
            throw new IllegalArgumentException("no backends configured");
        }
        if (maxRetries < 0) maxRetries = 0;
        // Parse eagerly so bad values fail at startup, not on first use.
        backendTimeout();
        healthCheckInterval();
        healthCheckTimeout();
        listenPort();
    }

    public Duration backendTimeout()      { return parseDuration(backendTimeout); }
    public Duration healthCheckInterval() { return parseDuration(healthCheckInterval); }
    public Duration healthCheckTimeout()  { return parseDuration(healthCheckTimeout); }

    /** Host part of "host:port"; empty means all interfaces. */
    public String listenHost() {
        int i = listen.lastIndexOf(':');
        return i <= 0 ? "" : listen.substring(0, i);
    }

    public int listenPort() {
        int i = listen.lastIndexOf(':');
        try {
            return Integer.parseInt(listen.substring(i + 1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid listen address: " + listen);
        }
    }

    private static final Pattern DURATION = Pattern.compile("(\\d+)(ms|s|m|h)");

    static Duration parseDuration(String s) {
        Matcher m = DURATION.matcher(s == null ? "" : s.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException("invalid duration \"" + s + "\" (use e.g. 500ms, 5s, 1m)");
        }
        long n = Long.parseLong(m.group(1));
        return switch (m.group(2)) {
            case "ms" -> Duration.ofMillis(n);
            case "s"  -> Duration.ofSeconds(n);
            case "m"  -> Duration.ofMinutes(n);
            default   -> Duration.ofHours(n);
        };
    }
}
