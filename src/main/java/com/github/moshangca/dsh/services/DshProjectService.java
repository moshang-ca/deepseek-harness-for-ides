package com.github.moshangca.dsh.services;

import com.github.moshangca.dsh.api.DshApiClient;
import com.github.moshangca.dsh.settings.DshSettingsState;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Project-scoped session on the IDE-wide {@link DshServerManager} server
 * (one server per IDE, one session per project). Exposes high-level
 * chat operations to the UI and routes its session's mux frames there.
 *
 * <p>Lifecycle: the first chat action lazily starts the shared server and
 * creates this project's session; closing the project releases its slot.</p>
 */
@Service(Service.Level.PROJECT)
public final class DshProjectService implements DshServerManager.SessionListener, Disposable {

    private static final Logger LOG = Logger.getInstance(DshProjectService.class);

    private final Project project;
    private final DshServerManager serverManager;
    private final AtomicReference<DshApiClient> clientRef = new AtomicReference<>();
    private final AtomicReference<String> sessionIdRef = new AtomicReference<>();

    private volatile @Nullable Listener uiListener;
    private boolean acquired;

    /** UI-facing callback interface, invoked on the EDT. */
    public interface Listener {
        /** A chunk of assistant text (final answer) arrived. */
        void onAssistantChunk(@NotNull String sessionId, @NotNull String text);

        /** A chunk of reasoning/thinking text arrived. */
        void onReasoningChunk(@NotNull String sessionId, @NotNull String text);

        /** The model started or finished a tool call. */
        void onToolCall(@NotNull String sessionId, @NotNull String callId, @NotNull String toolName,
                        @Nullable String arguments);

        /** A streaming fragment of a tool call's arguments. */
        void onToolCallDelta(@NotNull String sessionId, @NotNull String callId, @Nullable String toolName,
                             @NotNull String argumentsDelta);

        /** A tool call finished; resultText is the model-facing text (may be empty). */
        void onToolResult(@NotNull String sessionId, @NotNull String callId, @NotNull String resultText,
                          boolean isError);

        /** A turn started or ended. */
        void onTurn(@NotNull String sessionId, boolean started, @Nullable String reason);

        /** A permission request arrived; respond via {@link #respondToApproval}. */
        void onApprovalRequested(@NotNull String sessionId, @NotNull String rpcId, @NotNull String approvalId,
                                 @NotNull String toolName, @Nullable String callId);

        /** A permission request was resolved by the host. */
        void onApprovalResolved(@NotNull String sessionId, @NotNull String approvalId, @NotNull String outcome);

        /** The harness asked the user a batch of questions; respond via {@link #respondToQuestions}. */
        void onQuestionRequested(@NotNull String sessionId, @NotNull String rpcId, @NotNull JsonArray questions);

        /** The mux stream reported an error. */
        void onStreamError(@NotNull String message);

        /** The transient inbox snapshot changed. */
        void onQueueChanged(@NotNull String sessionId, int queued, int steering);

        /** The session's cumulative token usage changed (all four buckets). */
        void onTokenUsage(@NotNull String sessionId, long uncachedInput, long cacheRead,
                          long cacheWrite, long output);

        /** The session title changed. */
        void onTitleChanged(@NotNull String sessionId, @NotNull String title);

        /** A completed assistant message with its per-message token usage arrived. */
        void onAssistantMessage(@NotNull String sessionId, @NotNull String text,
                                long uncachedInput, long cacheRead, long output);

        /** The session list was refreshed (populate the history dropdown). */
        void onSessionListRefreshed(@NotNull JsonObject list);

        /** History for a switched-to session was loaded; replace the transcript. */
        void onHistoryLoaded(@NotNull String sessionId, @NotNull JsonArray events);

        /** Generic status message. */
        void onStatusChanged(@NotNull String status);
    }

    public DshProjectService(@NotNull Project project) {
        this.project = project;
        this.serverManager = DshServerManager.getInstance();
    }

    public static DshProjectService getInstance(@NotNull Project project) {
        return project.getService(DshProjectService.class);
    }

    public void setListener(@Nullable Listener listener) {
        this.uiListener = listener;
    }

    public boolean isServerRunning() {
        return serverManager.isRunning();
    }

    /**
     * Ensure the shared server is running and this project has a session.
     *
     * @param cwd the working directory for a newly created session (usually the project root)
     * @return the active session id
     */
    public @NotNull CompletableFuture<String> ensureSession(@NotNull String cwd) {
        String existing = sessionIdRef.get();
        if (existing != null) {
            return CompletableFuture.completedFuture(existing);
        }
        acquireOnce();
        return serverManager.ensureStarted(project).thenCompose(client -> {
            clientRef.set(client);
            return client.createSession(cwd);
        }).thenApply(result -> {
            String sessionId = result.get("sessionId").getAsString();
            sessionIdRef.set(sessionId);
            serverManager.registerSession(sessionId, this);
            applyDefaultModel(clientRef.get(), sessionId);
            status("session " + sessionId.substring(0, 8));
            return sessionId;
        });
    }

    private void acquireOnce() {
        if (acquired) return;
        acquired = true;
        serverManager.acquire();
    }

    public void sendMessage(@NotNull String text, @NotNull String cwd) {
        ensureSession(cwd).thenAccept(sessionId -> prompt(sessionId, text));
    }

    /** Cancel the active turn of the current session (no-op when idle). */
    public void cancel() {
        DshApiClient client = clientRef.get();
        String sessionId = sessionIdRef.get();
        if (client == null || sessionId == null) return;
        client.cancel(sessionId).whenComplete((result, error) -> {
            if (error != null) LOG.warn("cancel failed", error);
        });
    }

    public @NotNull CompletableFuture<String> newSession(@NotNull String cwd) {
        sessionIdRef.set(null);
        return ensureSession(cwd);
    }

    public void restartServer() {
        sessionIdRef.set(null);
        serverManager.restart();
        String cwd = project.getBasePath();
        if (cwd != null) {
            ensureSession(cwd);
        }
    }

    private void applyDefaultModel(@NotNull DshApiClient client, @NotNull String sessionId) {
        DshSettingsState settings = DshSettingsState.getInstance();
        if (settings.provider == null || settings.provider.isBlank()
                || settings.model == null || settings.model.isBlank()) {
            return;
        }
        String effort = settings.reasoningEffort;
        client.selectModel(sessionId, settings.provider, settings.model, effort).whenComplete((result, error) -> {
            if (error != null) LOG.warn("failed to apply default model " + settings.model, error);
        });
    }

    public @NotNull CompletableFuture<JsonObject> fetchModels() {
        DshApiClient client = clientRef.get();
        String sessionId = sessionIdRef.get();
        if (client == null || sessionId == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("dsh is not connected"));
        }
        return client.models(sessionId);
    }

    /**
     * Select a model with an optional reasoning effort and persist the choice.
     *
     * @param reasoningEffort the effort id ("" or null = provider default, e.g.
     *                        when the dropdown's Default entry is selected)
     */
    public void selectModel(@NotNull String provider, @NotNull String model,
                            @Nullable String reasoningEffort) {
        DshSettingsState settings = DshSettingsState.getInstance();
        settings.provider = provider;
        settings.model = model;
        settings.reasoningEffort = reasoningEffort == null ? "" : reasoningEffort;
        settings.save();
        DshApiClient client = clientRef.get();
        String sessionId = sessionIdRef.get();
        if (client == null || sessionId == null) {
            return;
        }
        client.selectModel(sessionId, provider, model, reasoningEffort).whenComplete((result, error) -> {
            if (error != null) {
                LOG.warn("selectModel failed", error);
                ApplicationManager.getApplication().invokeLater(() -> status("model switch failed: " + error.getMessage()));
            } else {
                String effortText = reasoningEffort == null || reasoningEffort.isEmpty()
                        ? "" : " · effort " + reasoningEffort;
                ApplicationManager.getApplication().invokeLater(() -> status("model: " + model + effortText));
            }
        });
    }

    /**
     * Fetch the server's session list and hand it to the UI for the history
     * dropdown. Requires the server to be started; no-ops when it is not.
     */
    public void refreshSessionList() {
        serverManager.ensureStarted(project).thenCompose(client -> {
            clientRef.set(client);
            return serverManager.listSessions();
        }).whenComplete((list, error) -> {
            if (error != null) {
                LOG.warn("session list fetch failed", error);
                return;
            }
            ApplicationManager.getApplication().invokeLater(() -> {
                Listener l = uiListener;
                if (l != null) l.onSessionListRefreshed(list);
            });
        });
    }

    /**
     * Load a session's history (the raw SessionEvent list) for display.
     *
     * @param sessionId the session to load
     * @param maxMessages maximum messages to fetch (nullable for host default)
     */
    public void fetchHistory(@NotNull String sessionId, @Nullable Integer maxMessages) {
        DshApiClient client = clientRef.get();
        if (client == null) {
            LOG.warn("cannot fetch history without an active dsh connection");
            return;
        }
        client.history(sessionId, maxMessages).whenComplete((result, error) -> {
            if (error != null) {
                LOG.warn("history fetch failed", error);
                ApplicationManager.getApplication().invokeLater(() -> {
                    Listener l = uiListener;
                    if (l != null) l.onStatusChanged("history load failed: " + error.getMessage());
                });
                return;
            }
            JsonArray events = result.has("events") && result.get("events").isJsonArray()
                    ? result.getAsJsonArray("events") : new JsonArray();
            ApplicationManager.getApplication().invokeLater(() -> {
                Listener l = uiListener;
                if (l != null) l.onHistoryLoaded(sessionId, events);
            });
        });
    }

    /**
     * Switch the project's active session to another one (from the history
     * dropdown). Cancels the current session, unregisters its listener, and
     * hands the history events to the UI for a full transcript rebuild.
     *
     * @param newSessionId the session to switch to
     */
    public void switchSession(@NotNull String newSessionId) {
        String oldSessionId = sessionIdRef.get();
        if (oldSessionId != null && oldSessionId.equals(newSessionId)) {
            return;
        }
        if (oldSessionId != null) {
            serverManager.unregisterSession(oldSessionId);
            DshApiClient client = clientRef.get();
            if (client != null) {
                try {
                    client.cancel(oldSessionId);
                } catch (Exception ignored) {
                }
            }
        }
        sessionIdRef.set(newSessionId);
        serverManager.registerSession(newSessionId, this);
        fetchHistory(newSessionId, null);
    }

    private void prompt(@NotNull String sessionId, @NotNull String text) {
        DshApiClient client = clientRef.get();
        if (client == null) {
            LOG.warn("cannot prompt without an active dsh connection");
            return;
        }
        client.prompt(sessionId, text).whenComplete((result, error) -> {
            if (error != null) {
                LOG.warn("prompt failed", error);
                ApplicationManager.getApplication().invokeLater(() -> {
                    Listener l = uiListener;
                    if (l != null) l.onStatusChanged("prompt error: " + error.getMessage());
                });
            }
        });
    }

    public void respondToApproval(@NotNull String rpcId, @NotNull String sessionId,
                                  @NotNull String approvalId, boolean allow) {
        DshApiClient client = clientRef.get();
        if (client == null) return;
        client.respondApproval(rpcId, sessionId, approvalId, allow).whenComplete((receipt, error) -> {
            if (error != null) {
                LOG.warn("approval respond failed", error);
            }
        });
    }

    @Override
    public void dispose() {
        String sessionId = sessionIdRef.get();
        if (sessionId != null) {
            serverManager.unregisterSession(sessionId);
            DshApiClient client = clientRef.getAndSet(null);
            if (client != null) {
                try {
                    client.cancel(sessionId);
                } catch (Exception ignored) {
                }
            }
        }
        sessionIdRef.set(null);
        if (acquired) {
            acquired = false;
            serverManager.release();
        }
    }

    @Override
    public void onSessionEvent(@NotNull String sessionId, @NotNull String type, @NotNull JsonObject data) {
        switch (type) {
            case "assistant/chunk" -> handleChunk(sessionId, data);
            case "assistant/message" -> handleAssistantMessage(sessionId, data);
            case "tool/call" -> handleToolCall(sessionId, data);
            case "tool/result" -> handleToolResult(sessionId, data);
            case "turn/start" -> ApplicationManager.getApplication().invokeLater(() -> {
                Listener l = uiListener;
                if (l != null) l.onTurn(sessionId, true, null);
            });
            case "turn/end" -> {
                String reason = data.has("reason") && data.get("reason").isJsonObject()
                        ? data.getAsJsonObject("reason").get("kind").getAsString() : "end";
                ApplicationManager.getApplication().invokeLater(() -> {
                    Listener l = uiListener;
                    if (l != null) l.onTurn(sessionId, false, reason);
                });
            }
            default -> LOG.debug("unhandled session event: " + type);
        }
    }

    /**
     * {@code assistant/message} carries the finalized assistant message plus its
     * per-message token usage ({@code data.usage}: inputTokens/cacheReadTokens/
     * outputTokens). The UI binds this to the streaming message that the text
     * deltas already rendered.
     */
    private void handleAssistantMessage(@NotNull String sessionId, @NotNull JsonObject data) {
        StringBuilder text = new StringBuilder();
        if (data.has("message") && data.get("message").isJsonObject()) {
            JsonObject message = data.getAsJsonObject("message");
            if (message.has("content") && message.get("content").isJsonArray()) {
                for (JsonElement blockEl : message.getAsJsonArray("content")) {
                    JsonObject block = blockEl.isJsonObject() ? blockEl.getAsJsonObject() : null;
                    if (block == null) continue;
                    if (!block.has("type") || !"text".equals(block.get("type").getAsString())) continue;
                    if (block.has("text")) {
                        if (!text.isEmpty()) text.append('\n');
                        text.append(block.get("text").getAsString());
                    }
                }
            }
        }
        long uncached = 0;
        long cacheRead = 0;
        long output = 0;
        if (data.has("usage") && data.get("usage").isJsonObject()) {
            JsonObject usage = data.getAsJsonObject("usage");
            uncached = usage.has("inputTokens") ? usage.get("inputTokens").getAsLong() : 0;
            cacheRead = usage.has("cacheReadTokens") ? usage.get("cacheReadTokens").getAsLong() : 0;
            output = usage.has("outputTokens") ? usage.get("outputTokens").getAsLong() : 0;
        }
        String finalText = text.toString();
        long finalUncached = uncached;
        long finalCacheRead = cacheRead;
        long finalOutput = output;
        ApplicationManager.getApplication().invokeLater(() -> {
            Listener l = uiListener;
            if (l != null) l.onAssistantMessage(sessionId, finalText, finalUncached, finalCacheRead, finalOutput);
        });
    }

    private void handleChunk(@NotNull String sessionId, @NotNull JsonObject data) {
        if (!data.has("chunk") || !data.get("chunk").isJsonObject()) return;
        JsonObject chunk = data.getAsJsonObject("chunk");
        String chunkType = chunk.has("type") ? chunk.get("type").getAsString() : "";
        switch (chunkType) {
            case "text-delta" -> {
                String text = chunk.has("text") ? chunk.get("text").getAsString() : "";
                if (text.isEmpty()) return;
                ApplicationManager.getApplication().invokeLater(() -> {
                    Listener l = uiListener;
                    if (l != null) l.onAssistantChunk(sessionId, text);
                });
            }
            case "reasoning-delta" -> {
                String text = chunk.has("text") ? chunk.get("text").getAsString() : "";
                if (text.isEmpty()) return;
                ApplicationManager.getApplication().invokeLater(() -> {
                    Listener l = uiListener;
                    if (l != null) l.onReasoningChunk(sessionId, text);
                });
            }
            case "tool-call-delta" -> {
                String callId = chunk.has("id") ? chunk.get("id").getAsString() : "";
                String name = chunk.has("name") && !chunk.get("name").isJsonNull()
                        ? chunk.get("name").getAsString() : null;
                String delta = chunk.has("argumentsDelta") ? chunk.get("argumentsDelta").getAsString() : "";
                if (callId.isEmpty()) return;
                ApplicationManager.getApplication().invokeLater(() -> {
                    Listener l = uiListener;
                    if (l != null) l.onToolCallDelta(sessionId, callId, name, delta);
                });
            }
            default -> LOG.debug("chunk type " + chunkType);
        }
    }

    private void handleToolCall(@NotNull String sessionId, @NotNull JsonObject data) {
        String callId = data.has("callId") ? data.get("callId").getAsString() : "";
        String name = data.has("name") ? data.get("name").getAsString() : "tool";
        String arguments = data.has("arguments") ? data.get("arguments").getAsString() : null;
        ApplicationManager.getApplication().invokeLater(() -> {
            Listener l = uiListener;
            if (l != null) l.onToolCall(sessionId, callId, name, arguments);
        });
    }

    private void handleToolResult(@NotNull String sessionId, @NotNull JsonObject data) {
        String callId = "";
        StringBuilder result = new StringBuilder();
        boolean isError = data.has("error") && data.get("error").isJsonObject();
        if (data.has("message") && data.get("message").isJsonObject()) {
            JsonObject message = data.getAsJsonObject("message");
            if (message.has("content") && message.get("content").isJsonArray()) {
                for (JsonElement blockEl : message.getAsJsonArray("content")) {
                    JsonObject block = blockEl.isJsonObject() ? blockEl.getAsJsonObject() : null;
                    if (block == null) continue;
                    if (block.has("toolCallId")) callId = block.get("toolCallId").getAsString();
                    if (block.has("isError")) isError |= block.get("isError").getAsBoolean();
                    if (!block.has("content") || !block.get("content").isJsonArray()) continue;
                    for (JsonElement cbEl : block.getAsJsonArray("content")) {
                        JsonObject cb = cbEl.isJsonObject() ? cbEl.getAsJsonObject() : null;
                        if (cb != null && cb.has("type") && "text".equals(cb.get("type").getAsString())
                                && cb.has("text")) {
                            if (!result.isEmpty()) result.append('\n');
                            result.append(cb.get("text").getAsString());
                        }
                    }
                }
            }
        }
        if (result.isEmpty() && isError) result.append("(tool execution failed)");
        String resultText = result.toString();
        String finalCallId = callId;
        boolean finalIsError = isError;
        ApplicationManager.getApplication().invokeLater(() -> {
            Listener l = uiListener;
            if (l != null) l.onToolResult(sessionId, finalCallId, resultText, finalIsError);
        });
    }

    @Override
    public void onApprovalRequested(@NotNull String sessionId, @NotNull String rpcId, @NotNull String approvalId,
                                    @NotNull String toolName, @Nullable String callId) {
        ApplicationManager.getApplication().invokeLater(() -> {
            Listener l = uiListener;
            if (l != null) l.onApprovalRequested(sessionId, rpcId, approvalId, toolName, callId);
        });
    }

    @Override
    public void onApprovalResolved(@NotNull String sessionId, @NotNull String approvalId, @NotNull String outcome) {
        ApplicationManager.getApplication().invokeLater(() -> {
            Listener l = uiListener;
            if (l != null) l.onApprovalResolved(sessionId, approvalId, outcome);
        });
    }

    public void respondToQuestions(@NotNull String rpcId, @NotNull String sessionId, @NotNull JsonArray answers) {
        DshApiClient client = clientRef.get();
        if (client == null) return;
        client.respondQuestions(rpcId, sessionId, answers).whenComplete((receipt, error) -> {
            if (error != null) LOG.warn("question respond failed", error);
        });
    }

    public void cancelQuestions(@NotNull String rpcId) {
        DshApiClient client = clientRef.get();
        if (client == null) return;
        client.cancelQuestion(rpcId).whenComplete((receipt, error) -> {
            if (error != null) LOG.warn("question cancel failed", error);
        });
    }

    @Override
    public void onQuestionRequested(@NotNull String sessionId, @NotNull String rpcId, @NotNull JsonArray questions) {
        ApplicationManager.getApplication().invokeLater(() -> {
            Listener l = uiListener;
            if (l != null) l.onQuestionRequested(sessionId, rpcId, questions);
        });
    }

    @Override
    public void onStreamError(@NotNull String message) {
        LOG.warn("dsh stream error: " + message);
        ApplicationManager.getApplication().invokeLater(() -> {
            Listener l = uiListener;
            if (l != null) l.onStreamError(message);
        });
    }

    @Override
    public void onQueueChanged(@NotNull String sessionId, int queued, int steering) {
        ApplicationManager.getApplication().invokeLater(() -> {
            Listener l = uiListener;
            if (l != null) l.onQueueChanged(sessionId, queued, steering);
        });
    }

    @Override
    public void onTokenUsage(@NotNull String sessionId, long uncachedInput, long cacheRead,
                             long cacheWrite, long output) {
        ApplicationManager.getApplication().invokeLater(() -> {
            Listener l = uiListener;
            if (l != null) l.onTokenUsage(sessionId, uncachedInput, cacheRead, cacheWrite, output);
        });
    }

    @Override
    public void onTitleChanged(@NotNull String sessionId, @NotNull String title) {
        ApplicationManager.getApplication().invokeLater(() -> {
            Listener l = uiListener;
            if (l != null) l.onTitleChanged(sessionId, title);
        });
    }

    @Override
    public void onDisconnected() {
        clientRef.set(null);
        sessionIdRef.set(null);
        ApplicationManager.getApplication().invokeLater(() -> status("disconnected"));
    }

    private void status(@NotNull String message) {
        ApplicationManager.getApplication().invokeLater(() -> {
            Listener l = uiListener;
            if (l != null) l.onStatusChanged(message);
        });
    }
}
