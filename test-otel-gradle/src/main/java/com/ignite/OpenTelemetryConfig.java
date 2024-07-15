package com.ignite;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;

import java.time.Duration;

public class OpenTelemetryConfig {

    private static final String SERVICE_NAME = "hello-service";
    private static LongCounter httpRequestCounter;

    public static void setup() {
        // Configure the OTLP gRPC exporter for traces
        OtlpGrpcSpanExporter otlpSpanExporter = OtlpGrpcSpanExporter.builder()
                .setEndpoint("http://localhost:4317") // Set the OTLP endpoint
                .build();

        // Set up the tracer provider with the OTLP exporter
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(BatchSpanProcessor.builder(otlpSpanExporter).build())
                .setResource(createResource())
                .build();

        // Configure the OTLP gRPC exporter for metrics
        OtlpGrpcMetricExporter otlpMetricExporter = OtlpGrpcMetricExporter.builder()
                .setEndpoint("http://localhost:4317") // Set the OTLP endpoint
                .build();

        // Set up the meter provider with the OTLP exporter
        SdkMeterProvider meterProvider = SdkMeterProvider.builder()
                .setResource(createResource())
                .registerMetricReader(PeriodicMetricReader.builder(otlpMetricExporter)
                        .setInterval(Duration.ofSeconds(10))
                        .build())
                .build();

        // Register the global OpenTelemetry SDK
        OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setMeterProvider(meterProvider)
                .buildAndRegisterGlobal();

        // Set up the meter and create the counter
        httpRequestCounter = GlobalOpenTelemetry.getMeter(SERVICE_NAME)
                .counterBuilder("http_requests_total")
                .setDescription("Total number of HTTP requests")
                .setUnit("1")
                .build();
    }

    private static Resource createResource() {
        return Resource.getDefault().toBuilder()
                .put("service.name", SERVICE_NAME)
                .build();
    }

    public static LongCounter getHttpRequestCounter() {
        return httpRequestCounter;
    }
}
