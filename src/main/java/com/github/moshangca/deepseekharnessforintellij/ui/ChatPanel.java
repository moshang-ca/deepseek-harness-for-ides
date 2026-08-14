package com.github.moshangca.deepseekharnessforintellij.ui;

import com.github.moshangca.deepseekharnessforintellij.dsh.DshConfig;
import com.github.moshangca.deepseekharnessforintellij.settings.DshSettingsState;
import com.github.moshangca.deepseekharnessforintellij.services.DshProjectService;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class ChatPanel extends JPanel implements DshProjectService.Listener {
    private static final Color STATUS_GREEN = new JBColor(0x3F8F3F, 0x62C462);
    private static final Color STATUS_YELLOW = new JBColor(0x9A8B16, 0xC9B458);
    private static final Color STATUS_RED = new JBColor(0xB4483C, 0xC0564B);
    private static final Color STATUS_MUTED = new JBColor(0x8A8A8A, 0x8A8A8A);
    private static final Color INPUT_BORDER_NORMAL = new JBColor(0xD5D5D5, 0x4E5054);
    private static final Color INPUT_BORDER_FOCUS = JBColor.namedColor("Focus.color", new JBColor(0x40B6FF, 0x40B6FF));
    private static final String PLACEHOLDER = "Type a message and press Enter...";

    private final Project project;
    private final DshProjectService service;

    private final JPanel messages = new ChatMessagesPanel();
    private final JBScrollPane scroll;
    private final JBTextArea input = new JBTextArea(5, 40);
    private final JLabel statusDot = new JLabel("●");
    private final JLabel statusLabel = new JLabel(" ");
    private final JComboBox<String> historyBox;
    private final JComboBox<String> modelBox;
    private final JComboBox<String> sandboxBox;

    private final List<ChatMessage> messageList = new ArrayList<>();
    /** Provider route per model-box item, parallel to the box's items. */
    private final List<String> modelProviders = new ArrayList<>();
    private boolean modelSelectionUpdating;

    private @Nullable ChatMessage streamingAssistant;
    private @Nullable ChatMessage streamingReasoning;

    public ChatPanel(@NotNull Project project) {
        super(new BorderLayout());
        this.project = project;
        this.service = DshProjectService.getInstance(project);

        scroll = new JBScrollPane(messages);
        scroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(JBUI.Borders.empty());

        input.setLineWrap(true);
        input.setWrapStyleWord(true);
        input.setOpaque(false);
        input.setBorder(JBUI.Borders.empty(4, 8));
        input.setText(PLACEHOLDER);
        input.setForeground(UIManager.getColor("Label.disabledForeground"));

        InputFrame inputFrame = new InputFrame(JBColor.background(), INPUT_BORDER_FOCUS);
        inputFrame.setBorder(JBUI.Borders.empty(2, 2));
        inputFrame.add(input, BorderLayout.CENTER);
        input.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                inputFrame.setBorderColor(INPUT_BORDER_FOCUS);
                if (PLACEHOLDER.equals(input.getText())) {
                    input.setText("");
                    input.setForeground(UIManager.getColor("TextArea.foreground"));
                }
            }

            @Override
            public void focusLost(FocusEvent e) {
                inputFrame.setBorderColor(INPUT_BORDER_NORMAL);
                if (input.getText().isEmpty()) {
                    input.setText(PLACEHOLDER);
                    input.setForeground(UIManager.getColor("Label.disabledForeground"));
                }
            }
        });

        JButton sendButton = new JButton(AllIcons.Actions.Execute);
        sendButton.setToolTipText("Send");
        sendButton.setPreferredSize(new Dimension(28, 28));
        sendButton.setFocusable(false);
        sendButton.addActionListener(e -> sendInput());
        inputFrame.addToBottom(sendButton, BorderLayout.EAST);

        // Model dropdown; populated from the harness catalog once connected.
        modelBox = new JComboBox<>();
        modelBox.setPrototypeDisplayValue("deepseek-v4-pro");
        modelBox.addItem(DshSettingsState.getInstance().model);
        modelBox.setToolTipText("Model (applies to the current session)");
        modelBox.addActionListener(e -> onModelChanged());

        // Sandbox mode dropdown (live switch restarts dsh).
        sandboxBox = new ComboBox<>(new String[]{
                DshConfig.SANDBOX_MODE_WORKSPACE,
                DshConfig.SANDBOX_MODE_FULL,
        });
        sandboxBox.setPrototypeDisplayValue(DshConfig.SANDBOX_MODE_FULL);
        sandboxBox.setSelectedItem(DshSettingsState.getInstance().sandboxMode);
        sandboxBox.setToolTipText("Sandbox mode (applies on restart)");
        sandboxBox.setFont(sandboxBox.getFont().deriveFont(11f));
        sandboxBox.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value,
                                                          int index, boolean isSelected, boolean cellHasFocus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(
                        list, value, index, isSelected, cellHasFocus);
                label.setFont(UIManager.getFont("ComboBox.font"));
                return label;
            }
        });
        inputFrame.addToBottom(sandboxBox, BorderLayout.WEST);

        // Model row: the model selector lives outside the input area.
        // JPanel modelRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        // modelRow.setOpaque(false);
        // modelRow.add(modelBox);

        JPanel inputArea = new JPanel(new BorderLayout());
        inputArea.add(inputFrame, BorderLayout.CENTER);
        JPanel bottomBar = new JPanel(new BorderLayout());
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        left.setOpaque(false);
        left.add(modelBox);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 2));
        right.setOpaque(false);
        bottomBar.add(left, BorderLayout.WEST);
        bottomBar.add(right, BorderLayout.EAST);
        inputArea.add(bottomBar, BorderLayout.SOUTH);

        JPanel south = new JPanel(new BorderLayout());
        south.setOpaque(false);
        // south.add(modelRow, BorderLayout.NORTH);
        south.add(inputArea, BorderLayout.CENTER);

        // Top status bar: status dot + text on the left, new-session and history on the right.
        statusDot.setForeground(STATUS_MUTED);
        statusLabel.setForeground(JBUI.CurrentTheme.Label.foreground(false));

        JPanel statusBar = new JPanel(new BorderLayout());
        statusBar.setOpaque(false);
        JPanel statusLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        statusLeft.setOpaque(false);
        statusLeft.add(statusDot);
        statusLeft.add(statusLabel);
        statusBar.add(statusLeft, BorderLayout.WEST);

        JButton newSessionButton = new JButton(AllIcons.General.Add);
        newSessionButton.setToolTipText("New Session");
        newSessionButton.setBorder(JBUI.Borders.empty());
        newSessionButton.setFocusable(false);
        newSessionButton.addActionListener(e -> newSession());

        historyBox = new JComboBox<>();
        historyBox.setToolTipText("Session history (coming soon)");
        historyBox.addItem("session history");
        JPanel statusRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        statusRight.setOpaque(false);
        statusRight.add(newSessionButton);
        statusRight.add(historyBox);
        statusBar.add(statusRight, BorderLayout.EAST);

        add(scroll, BorderLayout.CENTER);
        add(south, BorderLayout.SOUTH);
        add(statusBar, BorderLayout.NORTH);

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
            service.ensureSession(projectRoot).thenAccept(sessionId -> loadModels());
        }
    }

    private void loadModels() {
        service.fetchModels().whenComplete((catalog, error) -> {
            if (error != null || catalog == null) return;
            ApplicationManager.getApplication().invokeLater(() -> populateModels(catalog));
        });
    }

    private void populateModels(@NotNull JsonObject catalog) {
        JsonArray groups = catalog.has("groups") ? catalog.getAsJsonArray("groups") : null;
        if (groups == null) return;
        modelSelectionUpdating = true;
        try {
            modelBox.removeAllItems();
            modelProviders.clear();
            for (JsonElement groupEl : groups) {
                JsonObject group = groupEl.getAsJsonObject();
                String provider = group.has("id") ? group.get("id").getAsString() : "";
                JsonArray models = group.has("models") ? group.getAsJsonArray("models") : null;
                if (models == null) continue;
                for (JsonElement modelEl : models) {
                    String id = modelEl.getAsJsonObject().has("id")
                            ? modelEl.getAsJsonObject().get("id").getAsString() : "";
                    if (id.isEmpty()) continue;
                    modelBox.addItem(id);
                    modelProviders.add(provider);
                }
            }
            // Prefer the server's current selection, then the stored setting.
            String current = null;
            if (catalog.has("current") && catalog.get("current").isJsonObject()) {
                JsonObject cur = catalog.getAsJsonObject("current");
                if (cur.has("model")) current = cur.get("model").getAsString();
            }
            String target = current != null ? current : DshSettingsState.getInstance().model;
            if (target != null) modelBox.setSelectedItem(target);
        } finally {
            modelSelectionUpdating = false;
        }
    }

    private void onModelChanged() {
        if (modelSelectionUpdating) return;
        int index = modelBox.getSelectedIndex();
        if (index < 0 || index >= modelProviders.size()) return;
        String model = modelBox.getItemAt(index);
        String provider = modelProviders.get(index);
        service.selectModel(provider, model);
    }

    private void sendInput() {
        String text = input.getText();
        if (text == null || text.trim().isEmpty() || PLACEHOLDER.equals(text)) return;
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
            setStatus("switching sandbox mode to " + mode + "...");
            service.restartServer();
        }
    }

    private void addMessage(@NotNull ChatMessage message) {
        messageList.add(message);
        MessageBubble bubble = new MessageBubble(message, () -> {
            String rpcId = message.getRpcId();
            String approvalId = message.getApprovalId();
            if (rpcId != null && approvalId != null) {
                service.respondToApproval(rpcId, message.getSessionId(), approvalId, true);
                message.setApprovalState("allowed-once");
                updateMessage(message);
                setStatus("allowed");
            }
        }, () -> {
            String rpcId = message.getRpcId();
            String approvalId = message.getApprovalId();
            if (rpcId != null && approvalId != null) {
                service.respondToApproval(rpcId, message.getSessionId(), approvalId, false);
                message.setApprovalState("rejected");
                updateMessage(message);
                setStatus("denied");
            }
        }, () -> {
            message.setCollapsed(!message.isCollapsed());
            updateMessage(message);
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
        }, () -> {
            message.setCollapsed(!message.isCollapsed());
            updateMessage(message);
        });
        messages.add(bubble, index);
        messages.revalidate();
        scrollToBottom();
    }

    private void scrollToBottom() {
        SwingUtilities.invokeLater(() -> scroll.getVerticalScrollBar().setValue(
                scroll.getVerticalScrollBar().getMaximum()));
    }

    private @Nullable ChatMessage findToolCall(@NotNull String callId) {
        for (ChatMessage m : messageList) {
            if (m.getKind() == ChatMessage.Kind.TOOL_CALL && callId.equals(m.getToolCallId())) return m;
        }
        return null;
    }

    private @Nullable ChatMessage findPermission(@NotNull String approvalId) {
        for (ChatMessage m : messageList) {
            if (m.getKind() == ChatMessage.Kind.PERMISSION && approvalId.equals(m.getApprovalId())) return m;
        }
        return null;
    }

    @Override
    public void onAssistantChunk(@NotNull String sessionId, @NotNull String text) {
        setStatus("streaming");
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
    public void onToolCall(@NotNull String sessionId, @NotNull String callId, @NotNull String toolName,
                           @Nullable String arguments) {
        ChatMessage msg = findToolCall(callId);
        if (msg == null) {
            addMessage(ChatMessage.toolCall(sessionId, callId, toolName, arguments));
        } else {
            msg.setToolName(toolName);
            if (arguments != null) msg.setArguments(arguments);
            updateMessage(msg);
        }
    }

    @Override
    public void onToolCallDelta(@NotNull String sessionId, @NotNull String callId, @Nullable String toolName,
                                @NotNull String argumentsDelta) {
        ChatMessage msg = findToolCall(callId);
        if (msg == null) {
            msg = ChatMessage.toolCall(sessionId);
            msg.setToolCallId(callId);
            if (toolName != null) msg.setToolName(toolName);
            addMessage(msg);
        } else if (toolName != null) {
            msg.setToolName(toolName);
        }
        msg.appendArguments(argumentsDelta);
        updateMessage(msg);
    }

    @Override
    public void onToolResult(@NotNull String sessionId, @NotNull String callId, @NotNull String resultText,
                             boolean isError) {
        ChatMessage msg = findToolCall(callId);
        if (msg == null) {
            msg = ChatMessage.toolCall(sessionId, callId, "tool", null);
            addMessage(msg);
        }
        msg.setToolRunning(false);
        if (isError) {
            msg.setToolError(resultText.isBlank() ? "tool execution failed" : resultText);
        } else {
            msg.appendResult(resultText);
        }
        updateMessage(msg);
    }

    @Override
    public void onTurn(@NotNull String sessionId, boolean started, @Nullable String reason) {
        if (started) {
            setStatus("turn started");
        } else {
            streamingAssistant = null;
            streamingReasoning = null;
            setStatus("turn finished" + (reason != null ? ": " + reason : ""));
        }
    }

    @Override
    public void onApprovalRequested(@NotNull String sessionId, @NotNull String rpcId, @NotNull String approvalId,
                                    @NotNull String toolName, @Nullable String callId) {
        addMessage(ChatMessage.permission(sessionId, rpcId, approvalId, toolName, callId));
    }

    @Override
    public void onApprovalResolved(@NotNull String sessionId, @NotNull String approvalId, @NotNull String outcome) {
        ChatMessage msg = findPermission(approvalId);
        if (msg == null) return;
        msg.setApprovalState(outcome);
        updateMessage(msg);
    }

    @Override
    public void onQuestionRequested(@NotNull String sessionId, @NotNull String rpcId, @NotNull JsonArray questions) {
        QuestionDialog dialog = new QuestionDialog(project, questions);
        if (dialog.showAndGet()) {
            service.respondToQuestions(rpcId, sessionId, dialog.getAnswers());
        } else {
            service.cancelQuestions(rpcId);
        }
    }

    @Override
    public void onStreamError(@NotNull String message) {
        setStatus("stream error: " + message);
        addMessage(ChatMessage.status("", "error: " + message));
    }

    @Override
    public void onQueueChanged(@NotNull String sessionId, int queued, int steering) {
        if (queued > 0 || steering > 0) {
            setStatus("waiting... (" + queued + " queued"
                    + (steering > 0 ? ", " + steering + " steering" : "") + ")");
        }
    }

    @Override
    public void onStatusChanged(@NotNull String status) {
        setStatus(status);
    }

    private void setStatus(@NotNull String status) {
        statusLabel.setText(status);
        statusDot.setForeground(statusDotColor(status));
    }

    private static Color statusDotColor(@NotNull String status) {
        String s = status.toLowerCase();
        if (s.contains("exited") || s.contains("disconnect") || s.contains("error") || s.contains("failed")) {
            return STATUS_RED;
        }
        if (s.contains("starting") || s.contains("switching") || s.contains("waiting") || s.contains("install")) {
            return STATUS_YELLOW;
        }
        return STATUS_GREEN;
    }

    /** Rounded input frame; the border color highlights while the input is focused. */
    private static final class InputFrame extends JPanel {
        private final int arc = JBUI.scale(12);
        private Color borderColor;
        private final JPanel bottomPanel;
        private final List<JComponent> innerComponents = new ArrayList<>();

        InputFrame(Color background, Color border) {
            super(new BorderLayout());
            setOpaque(false);
            setBackground(background);
            this.borderColor = border;

            bottomPanel = new JPanel(new BorderLayout());
            bottomPanel.setOpaque(false);
            bottomPanel.setBorder(JBUI.Borders.empty(2, 8, 4, 8));
            add(bottomPanel, BorderLayout.SOUTH);
        }

        void setBorderColor(Color color) {
            this.borderColor = color;
            repaint();
        }

        public void addToBottom(JComponent component, Object direction) {
            bottomPanel.add(component, direction);
            innerComponents.add(component);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(getBackground());
            g2.fillRoundRect(5, 0, getWidth() - 10, getHeight() - 1, arc, arc);
            g2.setColor(borderColor);
            g2.drawRoundRect(5, 0, getWidth() - 10, getHeight() - 1, arc, arc);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** Messages panel that always tracks the viewport width (rows wrap to it). */
    private static final class ChatMessagesPanel extends JPanel implements Scrollable {
        ChatMessagesPanel() {
            super();
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return visibleRect.height;
        }
    }
}
