package com.example.loadbalancer;

import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/**
 * A tiny demo upstream server for trying the load balancer locally.
 *
 * <pre>java -cp target/loadbalancer.jar com.example.loadbalancer.DemoBackend 9001</pre>
 */
public final class DemoBackend {

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 9001;
        String name = "backend-" + port;

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/health", ex -> {
            try (ex) {
                byte[] ok = "ok".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, ok.length);
                ex.getResponseBody().write(ok);
            }
        });
        server.createContext("/", ex -> {
            try (ex) {
                byte[] body = ("hello from " + name + ", path=" + ex.getRequestURI().getPath() + "\n")
                        .getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, body.length);
                ex.getResponseBody().write(body);
            }
        });
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        System.out.println(name + " listening on :" + port);
    }
}
