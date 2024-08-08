package com.clarifi.phoenix.ashes.server;


import com.clarifi.common.util.Logging;
import  com.clarifi.common.application.App;
import com.clarifi.phoenix.ashes.common.PackedDataSession;
import com.clarifi.phoenix.ashes.metrics.HelloHandler;
import com.clarifi.phoenix.ashes.metrics.MetricsConfig;
import com.clarifi.phoenix.ashes.metrics.OpenTelemetryConfig;
import io.undertow.Handlers;
import io.undertow.Undertow;
import io.undertow.server.HttpHandler;
import io.undertow.server.handlers.*;
import io.undertow.server.handlers.encoding.EncodingHandler;
import io.undertow.server.handlers.resource.FileResourceManager;
import io.undertow.server.handlers.resource.ResourceHandler;
import io.undertow.util.StatusCodes;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.Ignition;
import org.apache.ignite.cache.query.QueryCursor;
import org.apache.ignite.cache.query.ScanQuery;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.spi.discovery.tcp.TcpDiscoverySpi;
import org.apache.ignite.spi.discovery.tcp.ipfinder.multicast.TcpDiscoveryMulticastIpFinder;
import org.apache.logging.log4j.Logger;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;


import javax.cache.Cache;
import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

public class ServerApp {

    private static final Logger _logger = Logging.getLogger(ServerApp.class);
    public static final String PATH_API2 = "/api2";
    public static final String PREFIX_CACHE_USER_DATA_SESSIONS = "user-data-sessions";

    private final BlockingQueue<Runnable> queue;
    private final ExecutorService executor;
    private Ignite ignite;



    public ServerApp() {
        // todo: replace with Glav's threading library
        queue = new ArrayBlockingQueue<>(32);
        executor = new ThreadPoolExecutor(
                2, 4, 5L, TimeUnit.MINUTES, queue);
    }

    public Ignite getIgnite() {
        return ignite;
    }

    public ExecutorService getExecutor() {
        return executor;
    }

    public void start() {
        final IgniteConfiguration cfg = new IgniteConfiguration();



        cfg.setClientMode(true);
        cfg.setPeerClassLoadingEnabled(true);

        cfg.setWorkDirectory("/home/nicolae.ovidiu@ciq.internal/DataSessionMapReduce2-main/PhoenixPodman-data-sessions-map-reduce/api-server");

        // Setting up an IP Finder to ensure the client can locate the servers.
        final TcpDiscoveryMulticastIpFinder ipFinder = new TcpDiscoveryMulticastIpFinder();
        ipFinder.setAddresses(Collections.singletonList("127.0.0.1:47500..47509"));
        cfg.setDiscoverySpi(new TcpDiscoverySpi().setIpFinder(ipFinder));

        ignite = Ignition.start(cfg);

        System.out.println(">> Started the client node");

        System.out.printf("\tNode ID: %s\n\tOS: %s\tJRE: %s\n",
                ignite.cluster().localNode().id(),
                System.getProperty("os.name"),
                System.getProperty("java.runtime.name")
        );
    }

    public static void main(final String[] args) throws Throwable {
        // Set up OpenTelemetry configuration
        OpenTelemetryConfig.setup();

        try {
            // Create HTTP server on port 8080
            HttpServer server = HttpServer.create(new InetSocketAddress(8087), 0);

            // Set up the handler for the "/hello" endpoint
            server.createContext("/hello", new HelloHandler(OpenTelemetryConfig.getHttpRequestCounter()));

            // Start the server
            server.setExecutor(null); // creates a default executor
            server.start();

            System.out.println("Server started on port 8087");

        } catch (IOException e) {
            e.printStackTrace();
        }


        App.initBeforeAnythingElse(args, 0, "Server App", "1.0.0");

        final ServerApp appServer = new ServerApp();

        App.appMain(appServer::startup, appServer::shutdown);
    }

    private void startup(){
        this.start();

        final HttpHandler createUser = new ResponseCodeHandler(StatusCodes.NOT_FOUND);
        final HttpHandler getUser = new ResponseCodeHandler(StatusCodes.NOT_FOUND);

        final HttpHandler putNewDataSession = new LoggingHandler(new RequestBufferingHandler(new BlockingHandler(
                new DataSessionPutHandler(this)), 1));
        final HttpHandler cancelDataSession = new ResponseCodeHandler(StatusCodes.NOT_FOUND);
        final HttpHandler getDataSessionStatus = new ResponseCodeHandler(StatusCodes.NOT_FOUND);
        final HttpHandler getDataSession = new LoggingHandler(new EncodingHandler.Builder().build(null).wrap(
                new DataSessionGetHandler(this)));
        final HttpHandler postNewDataSession = new LoggingHandler(new RequestBufferingHandler(new BlockingHandler(
                new DataSessionPostHandler(this)), 1));
        final HttpHandler deleteDataSession = new TokenValidatorMiddleware(new LoggingHandler(new DataSessionDeleteHandler(this)));

        final HttpHandler countIssues = new LoggingHandler(new DataSessionCountIssuesHandler(this));
        final HttpHandler getTimeSeries = new LoggingHandler(new GetTimeSeriesHandler(this));
        final HttpHandler getCrossSectional = new ResponseCodeHandler(StatusCodes.NOT_FOUND);

        final HttpHandler fallback = new RequestDumpingHandler(new ResponseCodeHandler(StatusCodes.BAD_REQUEST));

        ResourceHandler resourceHandler = new ResourceHandler(new FileResourceManager(new File("/home/nicolae.ovidiu@ciq.internal/DataSessionMapReduce2-main/PhoenixPodman-data-sessions-map-reduce/static_web/swagger"), 1024))
                .addWelcomeFiles("index.html")
                .setDirectoryListingEnabled(true);

        final PathHandler handler = Handlers.path()
                .addPrefixPath("/swagger-ui", resourceHandler)
                .addPrefixPath(PATH_API2, Handlers.routing()
                        .get("/user/get/{userId}", getUser)
                        .put("/user/create/{userId}", createUser)

                        .put("/data-session/update", putNewDataSession)
                        .post("/data-session/new", postNewDataSession)
                        .get("/data-session/status/{sessionId}", getDataSessionStatus)
                        .get("/data-session/cancel/{sessionId}", cancelDataSession)
                        .get("/data-session/get/{sessionId}", getDataSession)
                        .delete("/data-session/delete/{sessionId}", deleteDataSession)
                        .get("/data-sessions/count-issues/{userId}", countIssues)

                        .get("/time-series/{issueId}/{date}/{dataItemId}", getTimeSeries)
                        .get("/cross-sectional/{issueId}/{date}", getCrossSectional)

                        .setFallbackHandler(fallback)
                );

        final Undertow httpServer = Undertow.builder()
                .addHttpListener(8083, "localhost")
                .setHandler(handler)
                .build();

        httpServer.start();

        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        scheduler.scheduleAtFixedRate(new CacheCleanerTask(ignite),0,2,TimeUnit.MINUTES);
    }

    private void shutdown(){}

    private static class CacheCleanerTask implements Runnable{
        private final Ignite ignite;

        CacheCleanerTask(Ignite ignite){
            this.ignite = ignite;
        }
        @Override
        public void run() {
            System.out.println("Clearing Caches which are not used for over 2 minutes");
            Collection<String> cacheList = ignite.cacheNames();

            for(String cacheName : cacheList){
                final IgniteCache<UUID, PackedDataSession> userDataSessionCache = ignite.cache(cacheName);

                try(QueryCursor<Cache.Entry<UUID, PackedDataSession>> cursor =userDataSessionCache.query(new ScanQuery<>())){
                    for(Cache.Entry<UUID, PackedDataSession> entry : cursor){
                        Instant currentTime = Instant.now();
                        long minutesDifference = Duration.between(entry.getValue().getLastAccessedAt(),currentTime).toMinutes();
                        if(minutesDifference > 2){
                            final String userCacheName = String.format(
                                    "%s:%s", ServerApp.PREFIX_CACHE_USER_DATA_SESSIONS, entry.getValue().getUserId());
                            ignite.cache(userCacheName).clear();
                            System.out.println("Cache '"+userCacheName+ "' is cleared");
                        }
                    }
                }
            }
        }
    }
}
