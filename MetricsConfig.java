package com.clarifi.phoenix.ashes.metrics;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.MeterProvider;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;

import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

//import static jdk.internal.classfile.Classfile.build;

public class MetricsConfig {

    private final Meter meter;
    private final Tracer tracer;
    //private final Properties properties;

    public MetricsConfig() throws IOException{
        //properties = new Properties();
//        try (InputStream input = getClass().getClassLoader().getResourceAsStream("resources.properties")) {
//            if(input == null){
//                throw new IOException("Unable to find resources.properties");
//            }
//            properties.load(input);
//        }

        String metricsExportUrl = "http://localhost:4317/v1/metrics";
        String tracingEndpoint = "http://localhost:4317/v1/traces";
        int metricExportInterval = 10;

        OtlpGrpcMetricExporter metricExporter = OtlpGrpcMetricExporter.builder()
                .setEndpoint(metricsExportUrl)
                .build();

        SdkMeterProvider meterProvider = SdkMeterProvider.builder()
                .registerMetricReader(PeriodicMetricReader.builder(metricExporter)
                .setInterval(Duration.ofSeconds(metricExportInterval))
                .build())
        .build();

        OtlpGrpcSpanExporter spanExporter = OtlpGrpcSpanExporter.builder()
                .setEndpoint(tracingEndpoint)
                .build();

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(BatchSpanProcessor.builder(spanExporter).build())
                .build();

        OpenTelemetrySdk openTelemetrySdk = OpenTelemetrySdk.builder()
                .setMeterProvider(meterProvider)
                .setTracerProvider(tracerProvider)
                .build();

        GlobalOpenTelemetry.set(openTelemetrySdk);

        this.meter = GlobalOpenTelemetry.get().getMeter("example-meter");
        this.tracer = GlobalOpenTelemetry.get().getTracer("example-tracer");
    }

    public void sendMetrics(){
        meter.counterBuilder("example_couunter")
                .setDescription("An example counter")
                .setUnit("1")
                .build()
                .add(1,Attributes.builder().put("label1", "value1").build());

        AtomicInteger gaugeValue = new AtomicInteger(42);
        meter.gaugeBuilder("example_gauge")
                .setDescription("An example gauge")
                .setUnit("1")
                .buildWithCallback(result -> result.record(gaugeValue.getAndSet(gaugeValue.get()), Attributes.builder().put("label2", "value2").build()));

    }

    public void reacordActiveThreadMetric(){
        meter.gaugeBuilder("active_threads_gauge")
                .setDescription("Number of active threads")
                .setUnit("threads")
                .buildWithCallback(result -> {
                    int activeThreads = Thread.activeCount();
                });


    }
}
