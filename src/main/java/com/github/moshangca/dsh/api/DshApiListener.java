package com.github.moshangca.dsh.api;

import com.google.gson.JsonArray;
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
     * @param callId     the tool call id (maybe null)
     */
    void onApprovalRequested(@NotNull String sessionId, @NotNull String rpcId, @NotNull String approvalId,
                             @NotNull String toolName, @Nullable String callId);

    /**
     * The host resolved a previously requested approval.
     *
     * @param sessionId  the session
     * @param approvalId the approval request id
     * @param outcome    one of {@code allowed-once}, {@code rejected},
     *                   {@code cancelled}, {@code unavailable}
     */
    void onApprovalResolved(@NotNull String sessionId, @NotNull String approvalId, @NotNull String outcome);

    /**
     * The harness asked the user a batch of questions (the {@code ask_user_question}
     * interaction). Answer via {@code /api/respond} with the frame's rpcId.
     *
     * @param sessionId the session
     * @param rpcId     the server-request rpcId used to answer via /api/respond
     * @param questions the {@code AskUserQuestionItem} array
     */
    void onQuestionRequested(@NotNull String sessionId, @NotNull String rpcId, @NotNull JsonArray questions);

    /**
     * The mux stream itself reported an error.
     *
     * @param message the error message
     */
    void onStreamError(@NotNull String message);

    /**
     * The transient inbox snapshot changed ({@code session/queue}).
     *
     * @param sessionId the session
     * @param queued    items waiting in the FIFO queue
     * @param steering  items being steered into the active turn
     */
    void onQueueChanged(@NotNull String sessionId, int queued, int steering);

    /**
     * The mux stream was closed or the connection failed.
     */
    void onDisconnected();
}
