package com.ignite;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

public class MetricConfig {

    private final Meter meter;
    private final Tracer tracer;
    private final Properties properties;
    private final LongCounter httpRequestCounter;

    public MetricConfig() throws IOException {
        properties = new Properties();
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("config.properties")) {
            if (input == null) {
                throw new IOException("Sorry, unable to find config.properties");
            }
            properties.load(input);
        }

        String metricsExportUrl = properties.getProperty("management.otlp.metrics.export.url");
        String tracingEndpoint = properties.getProperty("management.otlp.tracing.endpoint");
        int metricExportInterval = Integer.parseInt(properties.getProperty("management.metrics.export.interval"));

        // Configure OTLP metric exporter
        OtlpGrpcMetricExporter metricExporter = OtlpGrpcMetricExporter.builder()
                .setEndpoint(metricsExportUrl)
                .build();

        // Set up a MeterProvider
        SdkMeterProvider meterProvider = SdkMeterProvider.builder()
                .registerMetricReader(PeriodicMetricReader.builder(metricExporter)
                        .setInterval(Duration.ofSeconds(metricExportInterval))
                        .build())
                .build();

        // Configure OTLP span exporter
        OtlpGrpcSpanExporter spanExporter = OtlpGrpcSpanExporter.builder()
                .setEndpoint(tracingEndpoint)
                .build();

        // Set up a TracerProvider
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(BatchSpanProcessor.builder(spanExporter).build())
                .build();

        // Register the MeterProvider and TracerProvider
        OpenTelemetrySdk openTelemetrySdk = OpenTelemetrySdk.builder()
                .setMeterProvider(meterProvider)
                .setTracerProvider(tracerProvider)
                .build();

        GlobalOpenTelemetry.set(openTelemetrySdk);

        // Get a meter
        this.meter = GlobalOpenTelemetry.get().getMeter("example-meter");

        // Get a tracer
        this.tracer = GlobalOpenTelemetry.get().getTracer("example-tracer");

        // Create a counter for HTTP requests
        this.httpRequestCounter = meter.counterBuilder("http_request_count")
                .setDescription("The total number of HTTP requests received")
                .setUnit("1")
                .build();
    }

    public LongCounter getHttpRequestCounter() {
        return httpRequestCounter;
    }

    public void sendMetrics() {
        // Create and record a counter metric
        meter.counterBuilder("example_counter")
                .setDescription("An example counter")
                .setUnit("1")
                .build()
                .add(1, Attributes.builder().put("label1", "value1").build());

        // Create and record a gauge metric
        AtomicInteger gaugeValue = new AtomicInteger(42);
        meter.gaugeBuilder("example_gauge")
                .setDescription("An example gauge")
                .setUnit("1")
                .buildWithCallback(result -> result.record(gaugeValue.getAndSet(gaugeValue.get()), Attributes.builder().put("label2", "value2").build()));
    }

    public void recordActiveThreadMetric() {
        // Create an observable gauge metric for active threads
        meter.gaugeBuilder("active_threads_gauge")
                .setDescription("Number of active threads")
                .setUnit("threads")
                .buildWithCallback(result -> {
                    int activeThreads = Thread.activeCount();
                    result.record(activeThreads);
                });

        // Simulate some threads being active (you should replace this with your actual thread logic)
        for (int i = 0; i < 10; i++) {
            new Thread(() -> {
                try {
                    Thread.sleep(2000); // Simulate thread activity
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }).start();
        }
    }

    public void sendTrace() {
        // Create a span
        Span span = tracer.spanBuilder("example-span")
                .setAttribute("attribute1", "value1")
                .startSpan();

        try {
            // Add events to the span
            span.addEvent("Event 1");
            span.addEvent("Event 2");

            // Simulate some work
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            span.setStatus(StatusCode.ERROR, "Thread interrupted");
            Thread.currentThread().interrupt();
        } finally {
            // End the span
            span.end();
        }
    }
}
