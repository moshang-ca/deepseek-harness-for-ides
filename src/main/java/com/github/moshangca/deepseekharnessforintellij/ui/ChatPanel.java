package com.github.moshangca.deepseekharnessforintellij.ui;

import com.github.moshangca.deepseekharnessforintellij.dsh.DshConfig;
import com.github.moshangca.deepseekharnessforintellij.settings.DshSettingsState;
import com.github.moshangca.deepseekharnessforintellij.services.DshProjectService;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class ChatPanel extends JPanel implements DshProjectService.Listener {

    private final Project project;
    private final DshProjectService service;

    private final JPanel messages = new JPanel();
    private final JBScrollPane scroll;
    private final JBTextArea input = new JBTextArea(4, 40);
    private final JLabel statusLabel = new JLabel(" ");
    private final JComboBox<String> sandboxBox;

    private final List<ChatMessage> messageList = new ArrayList<>();

    /** The assistant message currently receiving streamed text. */
    private @Nullable ChatMessage streamingAssistant;
    /** The reasoning message currently receiving streamed text. */
    private @Nullable ChatMessage streamingReasoning;

    public ChatPanel(@NotNull Project project) {
        super(new BorderLayout());
        this.project = project;
        this.service = DshProjectService.getInstance(project);

        messages.setLayout(new BoxLayout(messages, BoxLayout.Y_AXIS));
        scroll = new JBScrollPane(messages);
        scroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(JBUI.Borders.empty());

        input.setLineWrap(true);
        input.setWrapStyleWord(true);
        input.setBorder(JBUI.Borders.empty(6));

        JButton sendButton = new JButton("\u2708"); // paper plane
        sendButton.setToolTipText("Send");
        sendButton.setPreferredSize(new Dimension(30, 30));
        sendButton.setFocusable(false);
        sendButton.addActionListener(e -> sendInput());

        JButton newSessionButton = new JButton("New Session");
        newSessionButton.addActionListener(e -> newSession());

        // Sandbox mode dropdown (live switch restarts dsh).
        sandboxBox = new JComboBox<>(new String[]{
                DshConfig.SANDBOX_MODE_WORKSPACE,
                DshConfig.SANDBOX_MODE_FULL,
        });
        sandboxBox.setSelectedItem(DshSettingsState.getInstance().sandboxMode);
        sandboxBox.setToolTipText("Sandbox mode (applies on restart)");
        sandboxBox.addActionListener(e -> onSandboxChanged());

        JPanel inputArea = new JPanel(new BorderLayout());
        inputArea.add(input, BorderLayout.CENTER);
        JPanel bottomBar = new JPanel(new BorderLayout());
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        left.setOpaque(false);
        left.add(sandboxBox);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 2));
        right.setOpaque(false);
        right.add(newSessionButton);
        right.add(sendButton);
        bottomBar.add(left, BorderLayout.WEST);
        bottomBar.add(right, BorderLayout.EAST);
        inputArea.add(bottomBar, BorderLayout.SOUTH);

        statusLabel.setBorder(JBUI.Borders.empty(2, 6, 2, 6));
        statusLabel.setForeground(JBUI.CurrentTheme.Label.foreground(false));

        add(scroll, BorderLayout.CENTER);
        add(inputArea, BorderLayout.SOUTH);
        add(statusLabel, BorderLayout.NORTH);

        // Enter sends, Shift+Enter newline.
        input.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "send");
        input.getActionMap().put("send", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                sendInput();
            }
        });
        input.getInputMap().put(KeyStroke.getKeyStroke("shift ENTER"), "newline");
        input.getActionMap().put("newline", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                input.append("\n");
            }
        });

        service.setListener(this);
        String projectRoot = project.getBasePath();
        if (projectRoot != null) {
            service.ensureSession(projectRoot);
        }
    }

    private void sendInput() {
        String text = input.getText();
        if (text == null || text.trim().isEmpty()) return;
        input.setText("");
        streamingAssistant = null;
        streamingReasoning = null;

        String cwd = Objects.requireNonNullElse(project.getBasePath(), ".");
        addMessage(ChatMessage.user("", text));
        addMessage(ChatMessage.status("", "agent is working..."));
        service.sendMessage(text, cwd);
    }

    private void newSession() {
        streamingAssistant = null;
        streamingReasoning = null;
        messageList.clear();
        messages.removeAll();
        messages.revalidate();
        messages.repaint();
        service.newSession(Objects.requireNonNullElse(project.getBasePath(), "."));
    }

    private void onSandboxChanged() {
        Object selected = sandboxBox.getSelectedItem();
        if (selected == null) return;
        DshSettingsState settings = DshSettingsState.getInstance();
        String mode = (String) selected;
        if (!mode.equals(settings.sandboxMode)) {
            settings.sandboxMode = mode;
            statusLabel.setText("switching sandbox mode to " + mode + "...");
            service.restartServer();
        }
    }

    private void addMessage(@NotNull ChatMessage message) {
        messageList.add(message);
        MessageBubble bubble = new MessageBubble(message, () -> {
            // Allow
            String rpcId = message.getRpcId();
            String approvalId = message.getApprovalId();
            if (rpcId != null && approvalId != null) {
                service.respondToApproval(rpcId, message.getSessionId(), approvalId, true);
                statusLabel.setText("allowed");
            }
        }, () -> {
            String rpcId = message.getRpcId();
            String approvalId = message.getApprovalId();
            if (rpcId != null && approvalId != null) {
                service.respondToApproval(rpcId, message.getSessionId(), approvalId, false);
                statusLabel.setText("denied");
            }
        });
        messages.add(bubble);
        messages.revalidate();
        scrollToBottom();
    }

    private void updateMessage(@NotNull ChatMessage message) {
        int index = messageList.indexOf(message);
        if (index < 0) return;
        messages.remove(index);
        MessageBubble bubble = new MessageBubble(message, () -> {
        }, () -> {
        });
        messages.add(bubble, index);
        messages.revalidate();
        scrollToBottom();
    }

    private void scrollToBottom() {
        SwingUtilities.invokeLater(() -> scroll.getVerticalScrollBar().setValue(
                scroll.getVerticalScrollBar().getMaximum()));
    }

    // ---- DshProjectService.Listener (EDT) ----

    @Override
    public void onAssistantChunk(@NotNull String sessionId, @NotNull String text) {
        statusLabel.setText("streaming");
        if (streamingAssistant == null) {
            streamingAssistant = ChatMessage.assistant(sessionId, "");
            addMessage(streamingAssistant);
        }
        streamingAssistant.appendText(text);
        updateMessage(streamingAssistant);
    }

    @Override
    public void onReasoningChunk(@NotNull String sessionId, @NotNull String text) {
        if (streamingReasoning == null) {
            streamingReasoning = ChatMessage.reasoning(sessionId, "");
            addMessage(streamingReasoning);
        }
        streamingReasoning.appendText(text);
        updateMessage(streamingReasoning);
    }

    @Override
    public void onToolCall(@NotNull String sessionId, @NotNull String toolName, @Nullable String arguments) {
        addMessage(ChatMessage.toolCall(sessionId, toolName, arguments));
    }

    @Override
    public void onTurn(@NotNull String sessionId, boolean started, @Nullable String reason) {
        if (started) {
            statusLabel.setText("turn started");
        } else {
            streamingAssistant = null;
            streamingReasoning = null;
            statusLabel.setText("turn finished" + (reason != null ? ": " + reason : ""));
        }
    }

    @Override
    public void onApprovalRequested(@NotNull String sessionId, @NotNull String rpcId, @NotNull String approvalId,
                                    @NotNull String toolName, @Nullable String callId) {
        addMessage(ChatMessage.permission(sessionId, rpcId, approvalId, toolName, callId));
    }

    @Override
    public void onStatusChanged(@NotNull String status) {
        statusLabel.setText(status);
    }
}
