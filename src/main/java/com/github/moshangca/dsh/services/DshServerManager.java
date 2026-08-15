package com.github.moshangca.dsh.services;

import com.github.moshangca.dsh.api.DshApiClient;
import com.github.moshangca.dsh.api.DshApiEvents;
import com.github.moshangca.dsh.api.DshApiListener;
import com.github.moshangca.dsh.dsh.DshProcessManager;
import com.github.moshangca.dsh.settings.DshSettingsState;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Application-level owner of the single dsh web server this IDE runs.
 * <p>Owns the process, the unary RPC client, the mux event stream, and routes
 * session-scoped frames to the owning project's listener.
 */
@Service(Service.Level.APP)
public final class DshServerManager implements DshApiListener {

    private static final Logger LOG = Logger.getInstance(DshServerManager.class);

    /** Per-session event sink, implemented by the owning project service. */
    public interface SessionListener {
        void onSessionEvent(@NotNull String sessionId, @NotNull String type, @NotNull JsonObject data);

        void onApprovalRequested(@NotNull String sessionId, @NotNull String rpcId, @NotNull String approvalId,
                                 @NotNull String toolName, @Nullable String callId);

        void onApprovalResolved(@NotNull String sessionId, @NotNull String approvalId, @NotNull String outcome);

        void onQuestionRequested(@NotNull String sessionId, @NotNull String rpcId, @NotNull JsonArray questions);

        void onQueueChanged(@NotNull String sessionId, int queued, int steering);

        void onTokenUsage(@NotNull String sessionId, long uncachedInput, long cacheRead,
                          long cacheWrite, long output);

        void onTitleChanged(@NotNull String sessionId, @NotNull String title);

        void onStreamError(@NotNull String message);

        void onDisconnected();
    }

    private final DshProcessManager processManager;
    private final AtomicReference<DshApiClient> clientRef = new AtomicReference<>();
    private final AtomicReference<DshApiEvents> eventsRef = new AtomicReference<>();
    private final ConcurrentHashMap<String, SessionListener> listeners = new ConcurrentHashMap<>();

    private CompletableFuture<DshApiClient> pendingStart;
    private int userCount;

    public DshServerManager() {
        Path workDir = PathManager.getCommonDataPath().resolve("deepseek-harness-for-intellij");
        this.processManager = new DshProcessManager(workDir, DshSettingsState.getInstance().port);
    }

    public static DshServerManager getInstance() {
        return ApplicationManager.getApplication().getService(DshServerManager.class);
    }

    public synchronized void acquire() {
        userCount++;
    }

    public synchronized void release() {
        if (userCount > 0) userCount--;
        if (userCount <= 0) shutdown();
    }

    public synchronized void restart() {
        shutdown();
    }

    public boolean isRunning() {
        return processManager.isRunning();
    }

    public void registerSession(@NotNull String sessionId, @NotNull SessionListener listener) {
        listeners.put(sessionId, listener);
    }

    public void unregisterSession(@NotNull String sessionId) {
        listeners.remove(sessionId);
    }

    /**
     * List all sessions on the shared server. Requires the server to be
     * started; returns an empty value if it is not running.
     */
    public @NotNull CompletableFuture<JsonObject> listSessions() {
        DshApiClient client = clientRef.get();
        if (client == null) {
            return CompletableFuture.completedFuture(new JsonObject());
        }
        return client.listSessions();
    }

    /**
     * Bring the shared server up if needed and return the client. Runs the
     * startup modal on the calling (EDT) thread.
     */
    public @NotNull CompletableFuture<DshApiClient> ensureStarted(@Nullable Project project) {
        DshApiClient existing = clientRef.get();
        if (existing != null && processManager.isRunning()) {
            return CompletableFuture.completedFuture(existing);
        }
        synchronized (this) {
            existing = clientRef.get();
            if (existing != null && processManager.isRunning()) {
                return CompletableFuture.completedFuture(existing);
            }
            if (pendingStart != null) return pendingStart;
            pendingStart = doStart(project);
            return pendingStart;
        }
    }

    private CompletableFuture<DshApiClient> doStart(@Nullable Project project) {
        DshSettingsState settings = DshSettingsState.getInstance();
        AtomicReference<DshApiClient> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ProgressManager.getInstance().run(new Task.Modal(project, "Starting DeepSeek Harness", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                try {
                    result.set(startServer(settings, indicator));
                } catch (Throwable t) {
                    if (indicator.isCanceled()) {
                        processManager.stop();
                        failure.set(new ProcessCanceledException());
                    } else {
                        failure.set(t);
                    }
                }
            }
        });
        pendingStart = null;
        Throwable t = failure.get();
        if (t != null) return CompletableFuture.failedFuture(t);
        return CompletableFuture.completedFuture(result.get());
    }

    private @NotNull DshApiClient startServer(@NotNull DshSettingsState settings, @NotNull ProgressIndicator indicator) {
        AtomicReference<String> lastOutput = new AtomicReference<>("");
        if (!processManager.isNodeAvailable()) {
            throw new IllegalStateException("Node.js (node + npm) is required but was not found on PATH.");
        }
        indicator.setText("Installing DeepSeek Harness runtime");
        boolean installed = processManager.prepare(line -> {
            lastOutput.set(line);
            ApplicationManager.getApplication().invokeLater(() -> indicator.setText2(line));
        }, indicator::isCanceled);
        indicator.checkCanceled();
        if (!installed) {
            String detail = lastOutput.get();
            throw new IllegalStateException("Failed to install dsh packages: " + detail);
        }
        indicator.setText("Starting dsh web server");
        Process process = processManager.start(settings.sandboxMode);
        if (process == null) {
            throw new IllegalStateException("Failed to start dsh web server.");
        }
        if (!processManager.awaitReady(60_000)) {
            throw new IllegalStateException("dsh web server did not become ready within 60s.");
        }
        indicator.checkCanceled();
        String baseUrl = "http://127.0.0.1:" + processManager.getPort();
        DshApiClient client = new DshApiClient(baseUrl);
        clientRef.set(client);
        DshApiEvents events = new DshApiEvents(baseUrl, this);
        eventsRef.set(events);
        events.connect();
        processManager.setListener(exitCode -> LOG.info("dsh exited (" + exitCode + ")"));
        if (settings.apiKey != null && !settings.apiKey.isBlank()) {
            client.describeCredential("DEEPSEEK_API_KEY").whenComplete((describe, error) -> {
                if (error != null) {
                    LOG.warn("failed to inspect dsh credentials", error);
                    return;
                }
                if (!isCredentialWritable(describe, "DEEPSEEK_API_KEY")) {
                    LOG.info("DEEPSEEK_API_KEY is supplied by the launching environment; not setting it in credentials");
                    return;
                }
                client.setCredential("DEEPSEEK_API_KEY", settings.apiKey).whenComplete((r2, e2) -> {
                    if (e2 != null) LOG.warn("failed to store API key in dsh credentials", e2);
                });
            });
        }
        return client;
    }

    private static boolean isCredentialWritable(@NotNull JsonObject describe, @NotNull String ref) {
        if (!describe.has("credentials") || !describe.get("credentials").isJsonObject()) return true;
        JsonObject creds = describe.getAsJsonObject("credentials");
        if (!creds.has(ref) || !creds.get(ref).isJsonObject()) return true;
        JsonObject entry = creds.getAsJsonObject(ref);
        return !entry.has("writable") || entry.get("writable").getAsBoolean();
    }

    private void shutdown() {
        DshApiEvents events = eventsRef.getAndSet(null);
        if (events != null) events.close();
        clientRef.set(null);
        listeners.clear();
        processManager.stop();
    }

    @Override
    public void onSessionEvent(@NotNull String sessionId, @NotNull String type, @NotNull JsonObject data) {
        SessionListener l = listeners.get(sessionId);
        if (l != null) l.onSessionEvent(sessionId, type, data);
    }

    @Override
    public void onApprovalRequested(@NotNull String sessionId, @NotNull String rpcId, @NotNull String approvalId,
                                    @NotNull String toolName, @Nullable String callId) {
        SessionListener l = listeners.get(sessionId);
        if (l != null) l.onApprovalRequested(sessionId, rpcId, approvalId, toolName, callId);
    }

    @Override
    public void onApprovalResolved(@NotNull String sessionId, @NotNull String approvalId, @NotNull String outcome) {
        SessionListener l = listeners.get(sessionId);
        if (l != null) l.onApprovalResolved(sessionId, approvalId, outcome);
    }

    @Override
    public void onQuestionRequested(@NotNull String sessionId, @NotNull String rpcId, @NotNull JsonArray questions) {
        SessionListener l = listeners.get(sessionId);
        if (l != null) l.onQuestionRequested(sessionId, rpcId, questions);
    }

    @Override
    public void onQueueChanged(@NotNull String sessionId, int queued, int steering) {
        SessionListener l = listeners.get(sessionId);
        if (l != null) l.onQueueChanged(sessionId, queued, steering);
    }

    @Override
    public void onTokenUsage(@NotNull String sessionId, long uncachedInput, long cacheRead,
                             long cacheWrite, long output) {
        SessionListener l = listeners.get(sessionId);
        if (l != null) l.onTokenUsage(sessionId, uncachedInput, cacheRead, cacheWrite, output);
    }

    @Override
    public void onTitleChanged(@NotNull String sessionId, @NotNull String title) {
        SessionListener l = listeners.get(sessionId);
        if (l != null) l.onTitleChanged(sessionId, title);
    }

    @Override
    public void onStreamError(@NotNull String message) {
        for (SessionListener l : listeners.values()) l.onStreamError(message);
    }

    @Override
    public void onDisconnected() {
        clientRef.set(null);
        eventsRef.set(null);
        SessionListener[] snapshot = listeners.values().toArray(new SessionListener[0]);
        listeners.clear();
        for (SessionListener l : snapshot) l.onDisconnected();
    }
}
