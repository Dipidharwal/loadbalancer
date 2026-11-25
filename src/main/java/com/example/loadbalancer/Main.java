package com.example.loadbalancer;

import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Logger;

public final class Main {

    private static final Logger LOG = Logger.getLogger(Main.class.getName());

    public static void main(String[] args) throws Exception {
        System.setProperty("java.util.logging.SimpleFormatter.format", "%1$tF %1$tT %4$s %5$s%6$s%n");

        Path configPath = Path.of("config.json");
        for (int i = 0; i < args.length; i++) {
            if ((args[i].equals("-config") || args[i].equals("--config")) && i + 1 < args.length) {
                configPath = Path.of(args[++i]);
            }
        }

        Config cfg = Config.load(configPath);
        LoadBalancer lb = new LoadBalancer(cfg);

        HealthChecker health = new HealthChecker(lb.backends(), cfg.healthCheckPath,
                cfg.healthCheckInterval(), cfg.healthCheckTimeout());
        health.start();

        String host = cfg.listenHost();
        InetSocketAddress addr = host.isEmpty()
                ? new InetSocketAddress(cfg.listenPort())
                : new InetSocketAddress(host, cfg.listenPort());
        HttpServer server = LoadBalancer.createServer(lb, addr);
        server.start();
        LOG.info("load balancer listening on " + cfg.listen + " (strategy=" + cfg.strategy
                + ", " + lb.backends().size() + " backends)");

        // Graceful shutdown on Ctrl-C / SIGTERM: stop accepting, let in-flight requests finish.
        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("shutting down, draining in-flight requests...");
            health.close();
            server.stop(15);
            stopped.countDown();
        }));
        stopped.await();
    }
}
