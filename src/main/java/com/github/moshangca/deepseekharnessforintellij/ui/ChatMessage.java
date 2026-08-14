package com.github.moshangca.deepseekharnessforintellij.ui;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One chat message in the transcript. Immutable except for streaming state
 * (assistant/reasoning text, tool-call arguments/result), which is updated in
 * place.
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
    private @Nullable String approvalState;
    private @Nullable String callId;

    // Tool-call card fields
    private @Nullable String toolCallId;
    private @Nullable String toolName;
    private final StringBuilder arguments = new StringBuilder();
    private final StringBuilder resultText = new StringBuilder();
    private @Nullable String toolError;
    private boolean toolRunning;
    private boolean collapsed;

    private ChatMessage(Kind kind, String sessionId, @Nullable String initialText) {
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

    /** An empty tool-call card, typically created from the first streaming delta. */
    public static ChatMessage toolCall(String sessionId) {
        ChatMessage m = new ChatMessage(Kind.TOOL_CALL, sessionId, null);
        m.toolRunning = true;
        m.collapsed = true;
        return m;
    }

    public static ChatMessage toolCall(String sessionId, String callId, String toolName, @Nullable String arguments) {
        ChatMessage m = new ChatMessage(Kind.TOOL_CALL, sessionId, null);
        m.toolCallId = callId;
        m.toolName = toolName;
        if (arguments != null) m.arguments.append(arguments);
        m.toolRunning = true;
        m.collapsed = true;
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
        return text.isEmpty();
    }

    public @Nullable String getRpcId() {
        return rpcId;
    }

    public @Nullable String getApprovalId() {
        return approvalId;
    }

    public @Nullable String getApprovalState() {
        return approvalState;
    }

    public void setApprovalState(@Nullable String approvalState) {
        this.approvalState = approvalState;
    }

    public @Nullable String getCallId() {
        return callId;
    }

    public @Nullable String getToolCallId() {
        return toolCallId;
    }

    public void setToolCallId(@NotNull String toolCallId) {
        this.toolCallId = toolCallId;
    }

    public @Nullable String getToolName() {
        return toolName;
    }

    public void setToolName(@NotNull String toolName) {
        this.toolName = toolName;
    }

    public String getArguments() {
        return arguments.toString();
    }

    public void appendArguments(String s) {
        arguments.append(s);
    }

    public void setArguments(String s) {
        arguments.setLength(0);
        arguments.append(s);
    }

    public String getResultText() {
        return resultText.toString();
    }

    public void appendResult(String s) {
        if (!resultText.isEmpty()) resultText.append('\n');
        resultText.append(s);
    }

    public @Nullable String getToolError() {
        return toolError;
    }

    public void setToolError(@NotNull String toolError) {
        this.toolError = toolError;
    }

    public boolean isToolRunning() {
        return toolRunning;
    }

    public void setToolRunning(boolean toolRunning) {
        this.toolRunning = toolRunning;
    }

    /** Whether a REASONING message is collapsed to its header line. */
    public boolean isCollapsed() {
        return collapsed;
    }

    public void setCollapsed(boolean collapsed) {
        this.collapsed = collapsed;
    }
}
