package com.github.moshangca.dsh.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Consumes the dsh mux WebSocket stream ({@code /api/events.mux}) and
 * dispatches frames to a {@link DshApiListener}.
 *
 * <p>Verified frame shapes (server-request envelopes):</p>
 * <ul>
 *   <li>{@code session/event} — a durable {@code SessionEvent} (turn/start,
 *       step/start, user/message, assistant/chunk with reasoning-delta /
 *       text-delta / tool-call-delta, tool/call, tool/result, ...)</li>
 *   <li>{@code session/subscribed} — initial baseline per attached session</li>
 *   <li>{@code session/queue}, {@code session/projection} — state snapshots</li>
 *   <li>{@code approval/requested} — tool approval request (rpcId answers via
 *       {@code POST /api/respond})</li>
 * </ul>
 */
public final class DshApiEvents {

    private static final Logger LOG = Logger.getInstance(DshApiEvents.class);

    private final HttpClient http;
    private final String wsUrl;
    private final DshApiListener listener;

    private volatile @Nullable WebSocket socket;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public DshApiEvents(@NotNull String baseUrl, @NotNull DshApiListener listener) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.wsUrl = base.replaceFirst("^http", "ws") + "/api/events.mux";
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        this.listener = listener;
    }

    /**
     * Open the mux WebSocket. Non-blocking; frames flow to the listener from
     * the WebSocket read thread.
     *
     * @return a future completing when the socket is open
     */
    public CompletableFuture<Void> connect() {
        CompletableFuture<Void> opened = new CompletableFuture<>();
        http.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(URI.create(wsUrl), new WebSocket.Listener() {
                    private final StringBuilder text = new StringBuilder();

                    @Override
                    public void onOpen(WebSocket webSocket) {
                        socket = webSocket;
                        opened.complete(null);
                        webSocket.request(1);
                    }

                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        text.append(data);
                        if (last) {
                            String line = text.toString();
                            text.setLength(0);
                            try {
                                handleLine(line);
                            } catch (Exception e) {
                                LOG.warn("error handling mux frame", e);
                            }
                        }
                        webSocket.request(1);
                        return null;
                    }

                    @Override
                    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                        closed.set(true);
                        listener.onDisconnected();
                        return null;
                    }

                    @Override
                    public void onError(WebSocket webSocket, Throwable error) {
                        LOG.warn("mux websocket error: " + error.getMessage());
                        closed.set(true);
                        listener.onDisconnected();
                    }
                });
        return opened;
    }

    public void close() {
        closed.set(true);
        WebSocket ws = socket;
        if (ws != null) {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "plugin shutdown");
        }
    }

    public boolean isClosed() {
        return closed.get();
    }

    private void handleLine(@NotNull String line) {
        JsonObject message = JsonParser.parseString(line).getAsJsonObject();
        String rpcId = message.has("rpcId") ? message.get("rpcId").getAsString() : "";
        JsonObject payload = message.has("payload") && message.get("payload").isJsonObject()
                ? message.getAsJsonObject("payload") : new JsonObject();
        String type = payload.has("type") ? payload.get("type").getAsString() : "";
        String sessionId = payload.has("sessionId") ? payload.get("sessionId").getAsString() : "";

        switch (type) {
            case "session/event" -> {
                JsonObject event = payload.getAsJsonObject("event");
                String eventType = event.has("type") ? event.get("type").getAsString() : "";
                JsonObject data = event.has("data") && event.get("data").isJsonObject()
                        ? event.getAsJsonObject("data") : new JsonObject();
                listener.onSessionEvent(sessionId, eventType, data);
            }
            case "approval/requested" -> {
                String approvalId = payload.has("approvalId") ? payload.get("approvalId").getAsString() : "";
                String toolName = payload.has("toolName") ? payload.get("toolName").getAsString() : "";
                String callId = payload.has("callId") ? payload.get("callId").getAsString() : null;
                listener.onApprovalRequested(sessionId, rpcId, approvalId, toolName, callId);
            }
            case "approval/resolved" -> {
                String approvalId = payload.has("approvalId") ? payload.get("approvalId").getAsString() : "";
                String outcome = payload.has("outcome") ? payload.get("outcome").getAsString() : "";
                listener.onApprovalResolved(sessionId, approvalId, outcome);
            }
            case "question/requested" -> {
                JsonArray questions = payload.has("questions") && payload.get("questions").isJsonArray()
                        ? payload.getAsJsonArray("questions") : new JsonArray();
                listener.onQuestionRequested(sessionId, rpcId, questions);
            }
            case "stream/error" -> {
                String errorMessage = "stream error";
                if (payload.has("error") && payload.get("error").isJsonObject()
                        && payload.getAsJsonObject("error").has("message")) {
                    errorMessage = payload.getAsJsonObject("error").get("message").getAsString();
                }
                listener.onStreamError(errorMessage);
            }
            case "session/queue" -> {
                int queued = 0;
                int steering = 0;
                if (payload.has("items") && payload.get("items").isJsonArray()) {
                    for (JsonElement item : payload.getAsJsonArray("items")) {
                        JsonObject obj = item.isJsonObject() ? item.getAsJsonObject() : null;
                        if (obj == null) continue;
                        String placement = obj.has("placement") ? obj.get("placement").getAsString() : "";
                        if ("queued".equals(placement)) queued++;
                        else if ("steering".equals(placement)) steering++;
                    }
                }
                listener.onQueueChanged(sessionId, queued, steering);
            }
            case "session/projection" -> handleProjection(sessionId, payload);
            default -> LOG.debug("mux frame type=" + type + " session=" + sessionId);
        }
    }

    /**
     * {@code session/projection} carries per-session derived values keyed by
     * projection name. We consume {@code tokenUsage} (cumulative token buckets)
     * and {@code title} (auto-generated or user-set session title); all other
     * keys are ignored.
     */
    private void handleProjection(@NotNull String sessionId, @NotNull JsonObject payload) {
        String key = payload.has("key") ? payload.get("key").getAsString() : "";
        if (!payload.has("value") || !payload.get("value").isJsonObject()) return;
        JsonObject value = payload.getAsJsonObject("value");
        switch (key) {
            case "tokenUsage" -> {
                long uncached = value.has("uncachedInputTokens") ? value.get("uncachedInputTokens").getAsLong() : 0;
                long cacheRead = value.has("cacheReadTokens") ? value.get("cacheReadTokens").getAsLong() : 0;
                long cacheWrite = value.has("cacheWriteTokens") ? value.get("cacheWriteTokens").getAsLong() : 0;
                long output = value.has("outputTokens") ? value.get("outputTokens").getAsLong() : 0;
                listener.onTokenUsage(sessionId, uncached, cacheRead, cacheWrite, output);
            }
            case "title" -> {
                String title = value.has("title") && value.get("title").isJsonPrimitive()
                        ? value.get("title").getAsString() : "";
                listener.onTitleChanged(sessionId, title);
            }
            default -> LOG.debug("mux projection key=" + key + " session=" + sessionId);
        }
    }
}
