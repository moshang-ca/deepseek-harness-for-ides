package com.github.moshangca.deepseekharnessforintellij.api;

import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Listener for events dispatched from the dsh mux WebSocket stream
 * ({@code /api/events.mux}).
 *
 * <p>All callbacks are invoked on the WebSocket's read thread; the UI layer is
 * responsible for hopping to the EDT.</p>
 */
public interface DshApiListener {

    /**
     * A session event arrived on the mux stream.
     *
     * @param sessionId the session the event belongs to
     * @param type      the event type, e.g. {@code assistant/chunk},
     *                  {@code tool/call}, {@code tool/result}, {@code turn/start},
     *                  {@code turn/end}, {@code user/message}
     * @param data      the event payload object
     */
    void onSessionEvent(@NotNull String sessionId, @NotNull String type, @NotNull JsonObject data);

    /**
     * The host requested approval for a tool call.
     *
     * @param sessionId  the session
     * @param rpcId      the server-request rpcId used to answer via /api/respond
     * @param approvalId the approval request id used to respond
     * @param toolName   the tool requiring approval
     * @param callId     the tool call id (may be null)
     */
    void onApprovalRequested(@NotNull String sessionId, @NotNull String rpcId, @NotNull String approvalId,
                             @NotNull String toolName, @Nullable String callId);

    /**
     * The mux stream was closed or the connection failed.
     */
    void onDisconnected();
}
