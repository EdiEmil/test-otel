package com.clarifi.phoenix.ashes.server;

import com.clarifi.phoenix.ashes.common.DataSession;
import com.clarifi.phoenix.ashes.common.PackedDataSession;
import com.clarifi.phoenix.ashes.task.UpdateDataSessionTimestamp;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.PathTemplateMatch;
import io.undertow.util.StatusCodes;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.IgniteCompute;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

public class DataSessionGetHandler implements HttpHandler {
    private final ServerApp server;
    private static final Tracer tracer = GlobalOpenTelemetry.getTracer("com.clarifi.phoenix.ashes.server.DataSessionGetHandler");

    public DataSessionGetHandler(final ServerApp server) {
        this.server = server;
    }

    @Override
    public void handleRequest(final HttpServerExchange exchange) {
        Span span = tracer.spanBuilder("handleRequest").startSpan();
        try (Scope scope = span.makeCurrent()) {
            //-- Data sessionId is in the path (REST request)
            PathTemplateMatch pathMatch = exchange.getAttachment(PathTemplateMatch.ATTACHMENT_KEY);
            String sessionId = pathMatch.getParameters().get("sessionId");
            span.setAttribute("sessionId", sessionId);

            //-- UserId is passed as a query parameter
            final String userId = exchange.getQueryParameters().get("userId").getFirst();
            span.setAttribute("userId", userId);

            final Ignite ignite = server.getIgnite();

            final String userCacheName = String.format(
                    "%s:%s", ServerApp.PREFIX_CACHE_USER_DATA_SESSIONS, userId);

            final IgniteCache<UUID, PackedDataSession> userDataSessionCache = ignite.cache(userCacheName);
            if (userDataSessionCache == null) {
                span.setStatus(StatusCode.ERROR, "User session cache not found");
                exchange.setStatusCode(StatusCodes.NOT_FOUND);
                exchange.getResponseSender().send(String.format("User with id '%s' does not have any sessions", userId));
                exchange.endExchange();
            } else {
                final DataSession session = userDataSessionCache.get(UUID.fromString(sessionId));
                if (session == null) {
                    span.setStatus(StatusCode.ERROR, "Data session not found");
                    exchange.setStatusCode(StatusCodes.NOT_FOUND);
                    exchange.getResponseSender().send(String.format(
                            "Data session '%s' does not exist for user '%s'", sessionId, userId));
                    exchange.endExchange();
                } else {
                    final ExecutorService executor = server.getExecutor();
                    exchange.dispatch(executor, new Runnable() {
                        @Override
                        public void run() {
                            Span innerSpan = tracer.spanBuilder("processDataSession").startSpan();
                            try (Scope innerScope = innerSpan.makeCurrent()) {
                                final IgniteCompute compute = ignite.compute(ignite.cluster().forServers());
                                compute.runAsync(new UpdateDataSessionTimestamp(userId, sessionId));

                                final PackedDataSession.Writer writer = new PackedDataSession.JsonWriter(session);
                                final ByteArrayOutputStream output = new ByteArrayOutputStream();
                                writer.write(output);
                                final byte[] payload = output.toByteArray();

                                exchange.setStatusCode(StatusCodes.OK);
                                exchange.setResponseContentLength(payload.length);

                                exchange.getResponseHeaders().put(Headers.CONTENT_LENGTH, Integer.toString(payload.length));
                                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, writer.getMimeType());

                                exchange.getResponseSender().send(ByteBuffer.wrap(payload));
                                exchange.endExchange();

                                System.out.printf(
                                        "{Thread:%s} Data session sent: %s.\n",
                                        Thread.currentThread().getName(),
                                        session
                                );
                                innerSpan.setStatus(StatusCode.OK);
                            } catch (Exception e) {
                                innerSpan.setStatus(StatusCode.ERROR, e.getMessage());
                                throw e;
                            } finally {
                                innerSpan.end();
                            }
                        }
                    });
                }
            }
        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            throw e;
        } finally {
            span.end();
        }
    }
}
