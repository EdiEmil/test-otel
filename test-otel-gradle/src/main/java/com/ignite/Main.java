package com.ignite;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.net.InetSocketAddress;

public class Main {
    public static void main(String[] args) {
        // Set up OpenTelemetry configuration
        OpenTelemetryConfig.setup();

        try {
            // Create HTTP server on port 8080
            HttpServer server = HttpServer.create(new InetSocketAddress(8083), 0);

            // Set up the handler for the "/hello" endpoint
            server.createContext("/hello", new HelloHandler(OpenTelemetryConfig.getHttpRequestCounter()));

            // Start the server
            server.setExecutor(null); // creates a default executor
            server.start();

            System.out.println("Server started on port 8080");

        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
