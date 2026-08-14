package com.github.moshangca.deepseekharnessforintellij.api;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Minimal client for the dsh web API RPC layer.
 *
 * <p>Business calls are unary HTTP requests:
 * {@code POST /api/<namespace>/<method>} with a
 * {@code {type:"client-request", rpcId, method, payload}} envelope, receiving a
 * {@code {type:"server-response", rpcId, result:{ok,value|error}}} envelope.
 * All responses are correlated by the rpcId the caller mints.</p>
 *
 * <p>Session event streaming (mux WebSocket) is handled separately by
 * {@link DshApiEvents}.</p>
 */
public final class DshApiClient {

    private static final Logger LOG = Logger.getInstance(DshApiClient.class);

    private final Gson gson = new Gson();
    private final HttpClient http;
    private final String baseUrl;

    public DshApiClient(@NotNull String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        // Force HTTP/1.1: the dsh web server is a plain node:http HTTP/1.1
        // server that closes connections requesting an h2c upgrade, which the
        // default HTTP/2 client sends for http:// URLs.
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    /**
     * Call one unary RPC method.
     *
     * @param method  the wire method path, e.g. {@code session.prompt}
     * @param payload the request payload map (maybe empty)
     * @return a future resolving to the RPC result object (the {@code value} of {@code result})
     * @throws ApiRpcException when the call returns a non-ok result
     */
    public @NotNull CompletableFuture<JsonObject> call(@NotNull String method, @Nullable Map<String, Object> payload) {
        String rpcId = UUID.randomUUID().toString();
        Map<String, Object> request = Map.of(
                "type", "client-request",
                "rpcId", rpcId,
                "method", method,
                "payload", payload == null ? Map.of() : payload);
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/" + method))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(request)))
                .build();

        return http.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        throw new ApiTransportException("HTTP " + response.statusCode() + " for " + method);
                    }
                    JsonObject envelope;
                    try {
                        envelope = JsonParser.parseString(response.body()).getAsJsonObject();
                    } catch (Exception e) {
                        throw new ApiTransportException("malformed response for " + method + ": " + response.body());
                    }
                    if (!rpcId.equals(envelope.get("rpcId").getAsString())) {
                        throw new ApiTransportException("rpcId mismatch for " + method);
                    }
                    JsonObject result = envelope.getAsJsonObject("result");
                    boolean ok = result.get("ok").getAsBoolean();
                    if (!ok) {
                        JsonObject error = result.getAsJsonObject("error");
                        throw new ApiRpcException(
                                error.has("code") ? error.get("code").getAsString() : "unknown",
                                error.has("message") ? error.get("message").getAsString() : "RPC error");
                    }
                    JsonElement value = result.get("value");
                    return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
                });
    }

    public @NotNull CompletableFuture<JsonObject> describe() {
        return call("host.describe", Map.of());
    }

    public @NotNull CompletableFuture<JsonObject> createSession(@Nullable String cwd) {
        Map<String, Object> payload = cwd == null ? Map.of() : Map.of("cwd", cwd);
        return call("session.create", payload);
    }

    public @NotNull CompletableFuture<JsonObject> prompt(@NotNull String sessionId, @NotNull String text) {
        return call("session.prompt", Map.of(
                "sessionId", sessionId,
                "mode", "queue",
                "content", java.util.List.of(Map.of("type", "text", "text", text))));
    }

    public @NotNull CompletableFuture<JsonObject> cancel(@NotNull String sessionId) {
        return call("session.cancel", Map.of("sessionId", sessionId));
    }

    /**
     * Store a credential (e.g. {@code DEEPSEEK_API_KEY}) in the harness
     * credentials service. This is the runtime path the web Models page uses;
     * it works even when the server was launched without the value in its
     * environment, and the stored value survives server restarts.
     */
    public @NotNull CompletableFuture<JsonObject> setCredential(@NotNull String ref, @NotNull String value) {
        return call("credentials.set", Map.of("ref", ref, "value", value));
    }

    /**
     * Inspect a credential's state ({@code credentials.describe}). The
     * response carries {@code credentials.<ref>.writable}; false means the
     * value is supplied read-only by the launching environment and any
     * {@link #setCredential} call would be rejected.
     */
    public @NotNull CompletableFuture<JsonObject> describeCredential(@NotNull String ref) {
        return call("credentials.describe", Map.of("refs", java.util.List.of(ref)));
    }

    /**
     * Fetch the model catalog and the session's current selection via
     * {@code session.models}. The response value carries {@code current}
     * (provider/model/reasoningEffort) and {@code groups} (the available
     * providers and their models).
     */
    public @NotNull CompletableFuture<JsonObject> models(@NotNull String sessionId) {
        return call("session.models", Map.of("sessionId", sessionId));
    }

    public @NotNull CompletableFuture<JsonObject> selectModel(@NotNull String sessionId,
                                                              @NotNull String provider, @NotNull String model) {
        return call("session.selectModel", Map.of("sessionId", sessionId, "provider", provider, "model", model));
    }

    public @NotNull CompletableFuture<JsonObject> history(@NotNull String sessionId, @Nullable Integer maxMessages) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("sessionId", sessionId);
        if (maxMessages != null) payload.put("maxMessages", maxMessages);
        return call("session.history", payload);
    }

    /**
     * Answer an {@code approval/requested} frame. The response is a
     * client-response echoing the frame's rpcId, carried on {@code POST /api/respond};
     * the HTTP response body is an RpcReceipt.
     *
     * @param rpcId      the rpcId from the approval/requested frame
     * @param sessionId  the session id from the frame
     * @param approvalId the approvalId from the frame
     * @param allow      true for allowed-once, false for rejected
     * @return a future completing when the host acknowledges the receipt
     */
    public @NotNull CompletableFuture<JsonObject> respondApproval(@NotNull String rpcId, @NotNull String sessionId,
                                                                  @NotNull String approvalId, boolean allow) {
        return respond(rpcId, Map.of(
                "ok", true,
                "value", Map.of(
                        "sessionId", sessionId,
                        "approvalId", approvalId,
                        "outcome", allow ? "allowed-once" : "rejected")));
    }

    /**
     * Answer a {@code question/requested} frame with the user's answers.
     *
     * @param rpcId    the rpcId from the question/requested frame
     * @param sessionId the session id from the frame
     * @param answers  the answer array ({@code id}/{@code selected}/{@code custom})
     * @return a future completing when the host acknowledges the receipt
     */
    public @NotNull CompletableFuture<JsonObject> respondQuestions(@NotNull String rpcId, @NotNull String sessionId,
                                                                   @NotNull JsonArray answers) {
        return respond(rpcId, Map.of(
                "ok", true,
                "value", Map.of(
                        "sessionId", sessionId,
                        "answer", Map.of("answers", answers))));
    }

    /** Cancel a pending {@code question/requested} (the user dismissed it). */
    public @NotNull CompletableFuture<JsonObject> cancelQuestion(@NotNull String rpcId) {
        // The error body must match rpcErrorSchema: code 'cancelled' requires a
        // details object (may be empty), or the envelope is rejected as bad-response.
        return respond(rpcId, Map.of(
                "ok", false,
                "error", Map.of("code", "cancelled", "message", "question cancelled by user", "details", Map.of())));
    }

    /** Send one {@code client-response} envelope on {@code POST /api/respond}. */
    private @NotNull CompletableFuture<JsonObject> respond(@NotNull String rpcId, @NotNull Map<String, Object> result) {
        Map<String, Object> request = Map.of(
                "type", "client-response",
                "rpcId", rpcId,
                "result", result);
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/respond"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(request)))
                .build();

        return http.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        throw new ApiTransportException("HTTP " + response.statusCode() + " for /api/respond");
                    }
                    return JsonParser.parseString(response.body()).getAsJsonObject();
                });
    }

    /** A JSON-RPC-level error returned by the host. */
    public static final class ApiRpcException extends RuntimeException {
        private final String code;

        public ApiRpcException(String code, String message) {
            super("dsh RPC error " + code + ": " + message);
            this.code = code;
        }

        public String getCode() {
            return code;
        }
    }

    /** A transport-level failure (HTTP status, malformed body, rpcId mismatch). */
    public static final class ApiTransportException extends RuntimeException {
        public ApiTransportException(String message) {
            super(message);
        }

        public ApiTransportException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
