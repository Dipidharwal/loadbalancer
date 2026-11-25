package com.example.loadbalancer;

import com.example.loadbalancer.strategy.Strategy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/** An HTTP handler that spreads requests across a pool of backends. */
public final class LoadBalancer implements HttpHandler {

    private static final Logger LOG = Logger.getLogger(LoadBalancer.class.getName());

    /** Headers that apply to a single connection and must not be forwarded. */
    private static final Set<String> HOP_BY_HOP = caseInsensitive(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade");

    /** Headers java.net.http.HttpClient refuses to let us set, or that it sets itself. */
    private static final Set<String> RESTRICTED_REQUEST = caseInsensitive(
            "host", "content-length", "expect");

    private final List<Backend> backends;
    private final Strategy strategy;
    private final int maxRetries;
    private final Duration backendTimeout;
    private final long maxBodyBytes;
    private final HttpClient client;

    public LoadBalancer(Config cfg) {
        this.backends = cfg.backends.stream().map(bc -> {
            URI u = URI.create(bc.url);
            if (u.getScheme() == null || u.getHost() == null) {
                throw new IllegalArgumentException("invalid backend url: " + bc.url);
            }
            return new Backend(u, bc.weight);
        }).toList();
        this.strategy = Strategy.byName(cfg.strategy);
        this.maxRetries = cfg.maxRetries;
        this.backendTimeout = cfg.backendTimeout();
        this.maxBodyBytes = cfg.maxBodyBytes;
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)       // no h2c upgrade attempts
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER) // redirects go back to the client
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
    }

    public List<Backend> backends() { return backends; }

    /** Creates (but does not start) an HTTP server with the balancer and status endpoint mounted. */
    public static HttpServer createServer(LoadBalancer lb, InetSocketAddress addr) throws IOException {
        HttpServer server = HttpServer.create(addr, 0);
        server.createContext("/lb/status", lb::handleStatus);
        server.createContext("/", lb);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        return server;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        try (ex) {
            byte[] body;
            try {
                body = readBody(ex.getRequestBody());
            } catch (BodyTooLargeException e) {
                sendError(ex, 413, "413 Payload Too Large");
                return;
            }

            String clientIp = ex.getRemoteAddress().getAddress().getHostAddress();
            Set<Backend> tried = Collections.newSetFromMap(new IdentityHashMap<>());
            int attempts = 1 + maxRetries;

            for (int attempt = 1; attempt <= attempts; attempt++) {
                Backend b = strategy.next(backends, clientIp, tried);
                if (b == null) break;
                tried.add(b);

                b.beginRequest();
                try {
                    HttpResponse<InputStream> resp;
                    try {
                        resp = client.send(buildRequest(ex, b, body, clientIp),
                                HttpResponse.BodyHandlers.ofInputStream());
                    } catch (IOException e) {
                        b.recordFailure();
                        // Passive health check: take it out of rotation now;
                        // the active checker will bring it back when it recovers.
                        b.setAlive(false);
                        LOG.warning("backend " + b + " failed (" + describe(e) + "), marked down; attempt "
                                + attempt + "/" + attempts);
                        // A request with a body is only replayed if it never reached
                        // the backend (connection refused), to avoid duplicate writes.
                        if (body.length > 0 && !(e instanceof ConnectException)) break;
                        continue;
                    }
                    // Response headers are in. From here on we're committed to this backend.
                    relayResponse(ex, resp);
                    return;
                } finally {
                    b.endRequest();
                }
            }
            sendError(ex, 502, "502 Bad Gateway: no healthy backend available");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            // Usually the client disconnected mid-response; not the backend's fault.
            LOG.fine("client connection error: " + e);
        }
    }

    private HttpRequest buildRequest(HttpExchange ex, Backend b, byte[] body, String clientIp) {
        URI target = b.uri().resolve(ex.getRequestURI().getRawPath()
                + (ex.getRequestURI().getRawQuery() == null ? "" : "?" + ex.getRequestURI().getRawQuery()));

        HttpRequest.BodyPublisher publisher = body.length == 0
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(body);

        HttpRequest.Builder rb = HttpRequest.newBuilder(target)
                .timeout(backendTimeout) // time to wait for response headers
                .method(ex.getRequestMethod(), publisher);

        for (Map.Entry<String, List<String>> h : ex.getRequestHeaders().entrySet()) {
            String name = h.getKey();
            if (HOP_BY_HOP.contains(name) || RESTRICTED_REQUEST.contains(name)
                    || name.equalsIgnoreCase("x-forwarded-for")) continue;
            for (String v : h.getValue()) rb.header(name, v);
        }

        String prior = ex.getRequestHeaders().getFirst("X-Forwarded-For");
        rb.header("X-Forwarded-For", prior == null ? clientIp : prior + ", " + clientIp);
        String host = ex.getRequestHeaders().getFirst("Host");
        if (host != null) rb.header("X-Forwarded-Host", host);
        rb.header("X-Forwarded-Proto", "http");
        return rb.build();
    }

    private void relayResponse(HttpExchange ex, HttpResponse<InputStream> resp) throws IOException {
        resp.headers().map().forEach((name, values) -> {
            if (name.equals(":status") || HOP_BY_HOP.contains(name)
                    || name.equalsIgnoreCase("content-length")) return;
            ex.getResponseHeaders().put(name, values);
        });

        int status = resp.statusCode();
        long length = resp.headers().firstValueAsLong("content-length").orElse(-1);
        boolean noBody = ex.getRequestMethod().equalsIgnoreCase("HEAD")
                || status == 204 || status == 304 || (status >= 100 && status < 200);

        // HttpServer: -1 = no body, 0 = chunked, >0 = fixed length.
        long responseLength = noBody ? -1 : (length > 0 ? length : (length == 0 ? -1 : 0));
        ex.sendResponseHeaders(status, responseLength);

        try (InputStream in = resp.body()) {
            if (responseLength != -1) {
                try (OutputStream out = ex.getResponseBody()) {
                    in.transferTo(out);
                }
            }
        }
    }

    private void handleStatus(HttpExchange ex) throws IOException {
        try (ex) {
            byte[] json = STATUS_MAPPER.writeValueAsBytes(
                    Map.of("backends", backends.stream().map(Backend::status).toList()));
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, json.length);
            ex.getResponseBody().write(json);
        }
    }

    private static final ObjectMapper STATUS_MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    private byte[] readBody(InputStream in) throws IOException {
        byte[] data = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, maxBodyBytes + 1));
        if (data.length > maxBodyBytes) throw new BodyTooLargeException();
        return data;
    }

    private static void sendError(HttpExchange ex, int code, String msg) throws IOException {
        byte[] bytes = (msg + "\n").getBytes();
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        ex.getResponseBody().write(bytes);
    }

    private static String describe(IOException e) {
        return e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
    }

    private static Set<String> caseInsensitive(String... names) {
        Set<String> s = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        Collections.addAll(s, names);
        return s;
    }

    private static final class BodyTooLargeException extends IOException {}
}
