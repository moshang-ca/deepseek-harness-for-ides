package com.github.moshangca.deepseekharnessforintellij.services;

import com.github.moshangca.deepseekharnessforintellij.api.DshApiClient;
import com.github.moshangca.deepseekharnessforintellij.api.DshApiEvents;
import com.github.moshangca.deepseekharnessforintellij.api.DshApiListener;
import com.github.moshangca.deepseekharnessforintellij.dsh.DshProcessManager;
import com.github.moshangca.deepseekharnessforintellij.settings.DshSettingsState;
import com.google.gson.JsonObject;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.io.FileUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Project-scoped service wiring the dsh web server process to the
 * {@link DshApiClient}/{@link DshApiEvents} and exposing high-level chat
 * operations to the UI.
 *
 * <p>Lifecycle: the first chat action lazily starts the dsh web server; the
 * server is torn down when the project closes (this service is a
 * {@link Disposable} owned by the project).</p>
 */
@Service(Service.Level.PROJECT)
public final class DshProjectService implements DshApiListener, Disposable {

    private static final Logger LOG = Logger.getInstance(DshProjectService.class);

    private final Project project;
    private final DshProcessManager processManager;
    private final AtomicReference<DshApiClient> clientRef = new AtomicReference<>();
    private final AtomicReference<DshApiEvents> eventsRef = new AtomicReference<>();
    private final AtomicReference<String> sessionIdRef = new AtomicReference<>();

    private volatile @Nullable Listener uiListener;

    /** UI-facing callback interface, invoked on the EDT. */
    public interface Listener {
        /** A chunk of assistant text (final answer) arrived. */
        void onAssistantChunk(@NotNull String sessionId, @NotNull String text);

        /** A chunk of reasoning/thinking text arrived. */
        void onReasoningChunk(@NotNull String sessionId, @NotNull String text);

        /** The model started or finished a tool call. */
        void onToolCall(@NotNull String sessionId, @NotNull String toolName, @Nullable String arguments);

        /** A turn started or ended. */
        void onTurn(@NotNull String sessionId, boolean started, @Nullable String reason);

        /** A permission request arrived; respond via {@link #respondToApproval}. */
        void onApprovalRequested(@NotNull String sessionId, @NotNull String rpcId, @NotNull String approvalId,
                                 @NotNull String toolName, @Nullable String callId);

        /** Generic status message. */
        void onStatusChanged(@NotNull String status);
    }

    public DshProjectService(@NotNull Project project) {
        this.project = project;
        Path workDir = Path.of(FileUtil.getTempDirectory(), "dsh-for-intellij", String.valueOf(Math.abs(project.getName().hashCode())));
        int port = DshSettingsState.getInstance().port;
        this.processManager = new DshProcessManager(workDir, port);
    }

    public static DshProjectService getInstance(@NotNull Project project) {
        return project.getService(DshProjectService.class);
    }

    public void setListener(@Nullable Listener listener) {
        this.uiListener = listener;
    }

    public boolean isServerRunning() {
        return processManager.isRunning();
    }

    /**
     * Ensure the dsh server is running, creating a session if none exists.
     *
     * @param cwd the working directory for a newly created session (usually the project root)
     * @return the active session id
     */
    public @NotNull CompletableFuture<String> ensureSession(@NotNull String cwd) {
        String existing = sessionIdRef.get();
        if (existing != null) {
            return CompletableFuture.completedFuture(existing);
        }
        return startServerAndCreateSession(cwd);
    }

    public void sendMessage(@NotNull String text, @NotNull String cwd) {
        ensureSession(cwd).thenAccept(sessionId -> prompt(sessionId, text));
    }

    public void newSession(@NotNull String cwd) {
        sessionIdRef.set(null);
        ensureSession(cwd);
    }

    public void restartServer() {
        dispose();
        String cwd = project.getBasePath();
        if (cwd != null) {
            ensureSession(cwd);
        }
    }

    private @NotNull CompletableFuture<String> startServerAndCreateSession(@NotNull String cwd) {
        DshSettingsState settings = DshSettingsState.getInstance();

        return CompletableFuture.supplyAsync(() -> {
            status("starting dsh...");
            if (!processManager.isNodeAvailable()) {
                status("Node.js not found on PATH");
                throw new IllegalStateException("Node.js (node + npm) is required but was not found on PATH.");
            }
            if (!processManager.prepare()) {
                status("failed to prepare dsh runtime");
                throw new IllegalStateException("Failed to install dsh packages.");
            }
            Process process = processManager.start(settings.provider, settings.model,
                    settings.sandboxMode, settings.apiKey.isBlank() ? null : settings.apiKey);
            if (process == null) {
                status("failed to start dsh server");
                throw new IllegalStateException("Failed to start dsh web server.");
            }
            if (!processManager.awaitReady(60_000)) {
                status("dsh server did not become ready");
                throw new IllegalStateException("dsh web server did not become ready within 60s.");
            }
            String baseUrl = "http://127.0.0.1:" + processManager.getPort();
            DshApiClient client = new DshApiClient(baseUrl);
            clientRef.set(client);
            DshApiEvents events = new DshApiEvents(baseUrl, this);
            eventsRef.set(events);
            events.connect();
            processManager.setListener(exitCode -> {
                clientRef.set(null);
                eventsRef.set(null);
                sessionIdRef.set(null);
                ApplicationManager.getApplication().invokeLater(() -> status("dsh exited (" + exitCode + ")"));
            });
            status("connected");
            return client;
        }).thenCompose(client -> client.createSession(cwd))
                .thenApply(result -> {
                    String sessionId = result.get("sessionId").getAsString();
                    sessionIdRef.set(sessionId);
                    status("session " + sessionId.substring(0, 8));
                    return sessionId;
                });
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

    /** Answer an approval request. */
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
        DshApiEvents events = eventsRef.getAndSet(null);
        if (events != null) events.close();
        DshApiClient client = clientRef.getAndSet(null);
        if (client != null) {
            String sessionId = sessionIdRef.get();
            if (sessionId != null) {
                try {
                    client.cancel(sessionId);
                } catch (Exception ignored) {
                }
            }
        }
        processManager.stop();
        sessionIdRef.set(null);
    }

    @Override
    public void onSessionEvent(@NotNull String sessionId, @NotNull String type, @NotNull JsonObject data) {
        switch (type) {
            case "assistant/chunk" -> handleChunk(sessionId, data);
            case "tool/call" -> handleToolCall(sessionId, data);
            case "tool/result" -> {}
            case "turn/start" -> {
                String session = sessionId;
                ApplicationManager.getApplication().invokeLater(() -> {
                    Listener l = uiListener;
                    if (l != null) l.onTurn(session, true, null);
                });
            }
            case "turn/end" -> {
                String session = sessionId;
                String reason = data.has("reason") && data.get("reason").isJsonObject()
                        ? data.getAsJsonObject("reason").get("kind").getAsString() : "end";
                ApplicationManager.getApplication().invokeLater(() -> {
                    Listener l = uiListener;
                    if (l != null) l.onTurn(session, false, reason);
                });
            }
            default -> LOG.debug("unhandled session event: " + type);
        }
    }

    private void handleChunk(@NotNull String sessionId, @NotNull JsonObject data) {
        if (!data.has("chunk") || !data.get("chunk").isJsonObject()) return;
        JsonObject chunk = data.getAsJsonObject("chunk");
        String chunkType = chunk.has("type") ? chunk.get("type").getAsString() : "";
        String text = chunk.has("text") ? chunk.get("text").getAsString() : "";
        if (text.isEmpty()) return;
        String session = sessionId;
        switch (chunkType) {
            case "text-delta" -> ApplicationManager.getApplication().invokeLater(() -> {
                Listener l = uiListener;
                if (l != null) l.onAssistantChunk(session, text);
            });
            case "reasoning-delta" -> ApplicationManager.getApplication().invokeLater(() -> {
                Listener l = uiListener;
                if (l != null) l.onReasoningChunk(session, text);
            });
            default -> LOG.debug("chunk type " + chunkType);
        }
    }

    private void handleToolCall(@NotNull String sessionId, @NotNull JsonObject data) {
        String name = data.has("name") ? data.get("name").getAsString() : "tool";
        String arguments = data.has("arguments") ? data.get("arguments").getAsString() : null;
        String session = sessionId;
        ApplicationManager.getApplication().invokeLater(() -> {
            Listener l = uiListener;
            if (l != null) l.onToolCall(session, name, arguments);
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
    public void onDisconnected() {
        clientRef.set(null);
        ApplicationManager.getApplication().invokeLater(() -> status("disconnected"));
    }

    private void status(@NotNull String message) {
        ApplicationManager.getApplication().invokeLater(() -> {
            Listener l = uiListener;
            if (l != null) l.onStatusChanged(message);
        });
    }
}
