package com.github.moshangca.deepseekharnessforintellij.ui;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One chat message in the transcript. Immutable except for the streaming
 * assistant/reasoning text, which is appended to in place.
 */
public final class ChatMessage {

    public enum Kind {
        USER,
        ASSISTANT,
        REASONING,
        TOOL_CALL,
        PERMISSION,
        STATUS,
    }

    private final Kind kind;
    private final String sessionId;
    private final StringBuilder text = new StringBuilder();

    // Permission card fields
    private @Nullable String rpcId;
    private @Nullable String approvalId;
    private @Nullable String toolName;
    private @Nullable String callId;

    private ChatMessage(Kind kind, String sessionId, String initialText) {
        this.kind = kind;
        this.sessionId = sessionId;
        if (initialText != null) this.text.append(initialText);
    }

    public static ChatMessage user(String sessionId, String text) {
        return new ChatMessage(Kind.USER, sessionId, text);
    }

    public static ChatMessage assistant(String sessionId, String text) {
        return new ChatMessage(Kind.ASSISTANT, sessionId, text);
    }

    public static ChatMessage reasoning(String sessionId, String text) {
        return new ChatMessage(Kind.REASONING, sessionId, text);
    }

    public static ChatMessage toolCall(String sessionId, String toolName, @Nullable String arguments) {
        ChatMessage m = new ChatMessage(Kind.TOOL_CALL, sessionId, null);
        m.toolName = toolName;
        m.text.append(toolName);
        if (arguments != null && !arguments.isBlank()) {
            m.text.append("  ").append(arguments.length() > 120 ? arguments.substring(0, 120) + "…" : arguments);
        }
        return m;
    }

    public static ChatMessage permission(String sessionId, String rpcId, String approvalId,
                                         String toolName, @Nullable String callId) {
        ChatMessage m = new ChatMessage(Kind.PERMISSION, sessionId, null);
        m.rpcId = rpcId;
        m.approvalId = approvalId;
        m.toolName = toolName;
        m.callId = callId;
        m.text.append("Agent wants to run: ").append(toolName);
        return m;
    }

    public static ChatMessage status(String sessionId, String text) {
        return new ChatMessage(Kind.STATUS, sessionId, text);
    }

    public Kind getKind() {
        return kind;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void appendText(String s) {
        text.append(s);
    }

    @NotNull
    public String getText() {
        return text.toString();
    }

    public boolean isEmpty() {
        return text.length() == 0;
    }

    public @Nullable String getRpcId() {
        return rpcId;
    }

    public @Nullable String getApprovalId() {
        return approvalId;
    }

    public @Nullable String getToolName() {
        return toolName;
    }

    public @Nullable String getCallId() {
        return callId;
    }
}
