package com.clarifi.phoenix.ashes.metrics;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;

import java.io.IOException;
import java.io.OutputStream;

public class HelloHandler implements HttpHandler {

    private final LongCounter httpRequestCounter;
    private final Tracer tracer;

    public HelloHandler(LongCounter httpRequestCounter) {
        this.httpRequestCounter = httpRequestCounter;
        this.tracer = GlobalOpenTelemetry.get().getTracer("hello-handler");
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        // Start a new span
        Span span = tracer.spanBuilder("handleRequest")
                .setSpanKind(SpanKind.SERVER)
                .startSpan();

        try {
            // Increment the HTTP request counter
            httpRequestCounter.add(1, Attributes.builder().put("path", "/hello").build());

            // Respond with "Hello, World!"
            String response = "Hello, World!";
            exchange.sendResponseHeaders(200, response.getBytes().length);
            OutputStream os = exchange.getResponseBody();
            os.write(response.getBytes());
            os.close();

            // Set span attributes and status
            span.setAttribute("http.method", exchange.getRequestMethod());
            span.setAttribute("http.url", exchange.getRequestURI().toString());
            span.setStatus(StatusCode.OK);
        } catch (Exception e) {
            // Record exception and set span status to ERROR
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, "Exception occurred: " + e.getMessage());
            throw e;
        } finally {
            span.end(); // End the span
        }
    }
}