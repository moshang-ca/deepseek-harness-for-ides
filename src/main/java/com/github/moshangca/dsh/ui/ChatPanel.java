package com.github.moshangca.dsh.ui;

import com.github.moshangca.dsh.dsh.DshConfig;
import com.github.moshangca.dsh.settings.DshSettingsState;
import com.github.moshangca.dsh.services.DshProjectService;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.util.IconLoader;
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
    /** Red square shown on the send button while a turn runs; clicking cancels it. */
    private static final Icon CANCEL_ICON = IconLoader.getIcon("/icons/cancel.svg", ChatPanel.class);

    private final Project project;
    private final DshProjectService service;

    private final JPanel messages = new ChatMessagesPanel();
    private final JBScrollPane scroll;
    private final JBTextArea input = new JBTextArea(5, 40);
    private final JButton sendButton;
    private final JLabel statusDot = new JLabel("●");
    private final JLabel statusLabel = new JLabel(" ");
    private final JLabel tokenLabel = new JLabel(" ");
    private final JComboBox<SessionEntry> historyBox;
    private final JComboBox<String> modelBox;
    private final JComboBox<String> sandboxBox;
    private final JComboBox<EffortOption> effortBox;

    private final List<ChatMessage> messageList = new ArrayList<>();
    /** Provider route per model-box item, parallel to the box's items. */
    private final List<String> modelProviders = new ArrayList<>();
    /** Reasoning effort options per model-box item, parallel to the box's items. */
    private final List<List<EffortOption>> modelEffortOptions = new ArrayList<>();
    /** The deployment's default effort per model-box item ("" = none), parallel to the box's items. */
    private final List<String> modelDefaultEfforts = new ArrayList<>();
    private boolean modelSelectionUpdating;
    private boolean effortUpdating;
    /** Whether a turn is running; drives the send/cancel button state. */
    private boolean turnActive;
    /** Current session id (may differ from service's if a history switch is pending). */
    private volatile @Nullable String activeSessionId;

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
        inputFrame.setBorder(JBUI.Borders.empty(2));
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

        sendButton = new JButton(AllIcons.Actions.Execute);
        sendButton.setToolTipText("Send");
        sendButton.setPreferredSize(new Dimension(28, 28));
        sendButton.setFocusable(false);
        sendButton.setBorder(JBUI.Borders.empty());
        sendButton.addActionListener(e -> onSendButtonClicked());
        inputFrame.addToBottom(sendButton, BorderLayout.EAST);

        // Model dropdown; populated from the harness catalog once connected.
        modelBox = new ComboBox<>();
        modelBox.setPrototypeDisplayValue("deepseek-v4-pro");
        modelBox.addItem(DshSettingsState.getInstance().model);
        modelBox.setToolTipText("Model (applies to the current session)");
        modelBox.addActionListener(e -> onModelChanged());

        effortBox = new ComboBox<>();
        effortBox.setPrototypeDisplayValue(EffortOption.DEFAULT);
        effortBox.addItem(EffortOption.DEFAULT);
        effortBox.setToolTipText("Thinking effort for the current model (applies from the next turn)");
        effortBox.setFont(effortBox.getFont().deriveFont(11f));
        effortBox.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value,
                                                          int index, boolean isSelected, boolean cellHasFocus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(
                        list, value, index, isSelected, cellHasFocus);
                label.setFont(UIManager.getFont("ComboBox.font"));
                return label;
            }
        });
        effortBox.setEnabled(false);
        effortBox.addActionListener(e -> onEffortChanged());

        // Sandbox mode dropdown (live switch restarts dsh).
        sandboxBox = new ComboBox<>(DshConfig.SANDBOX_MODES);
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
        sandboxBox.addActionListener(e -> onSandboxChanged());
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
        left.add(effortBox);
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
        statusLeft.add(tokenLabel);
        tokenLabel.setForeground(JBUI.CurrentTheme.Label.foreground(false));
        statusBar.add(statusLeft, BorderLayout.WEST);

        JButton newSessionButton = new JButton(AllIcons.General.Add);
        newSessionButton.setToolTipText("New Session");
        newSessionButton.setBorder(JBUI.Borders.empty());
        newSessionButton.setFocusable(false);
        newSessionButton.addActionListener(e -> newSession());

        historyBox = new ComboBox<>();
        historyBox.setToolTipText("Session history: switch to a past session");
        historyBox.setPrototypeDisplayValue(new SessionEntry("00000000", "deepseek-v4-pro"));
        historyBox.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value,
                                                          int index, boolean isSelected, boolean cellHasFocus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(
                        list, value, index, isSelected, cellHasFocus);
                if (value instanceof SessionEntry(String sessionId, String title)) {
                    label.setText(title.isEmpty() ? sessionId.substring(0, 8) : title);
                    label.setToolTipText(sessionId);
                }
                return label;
            }
        });
        historyBox.addActionListener(e -> onHistorySelected());
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
            service.ensureSession(projectRoot).thenAccept(sessionId -> {
                activeSessionId = sessionId;
                loadModels();
                service.refreshSessionList();
            });
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
            modelEffortOptions.clear();
            modelDefaultEfforts.clear();
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
                    modelEffortOptions.add(parseEffortOptions(modelEl.getAsJsonObject()));
                    modelDefaultEfforts.add(parseDefaultEffort(modelEl.getAsJsonObject()));
                }
            }
            // Prefer the server's current selection, then the stored setting.
            String current = null;
            String currentEffort = null;
            if (catalog.has("current") && catalog.get("current").isJsonObject()) {
                JsonObject cur = catalog.getAsJsonObject("current");
                if (cur.has("model")) current = cur.get("model").getAsString();
                if (cur.has("reasoningEffort") && cur.get("reasoningEffort").isJsonPrimitive()) {
                    currentEffort = cur.get("reasoningEffort").getAsString();
                }
            }
            String target = current != null ? current : DshSettingsState.getInstance().model;
            if (target != null) modelBox.setSelectedItem(target);
            populateEffortForSelection(currentEffort);
        } finally {
            modelSelectionUpdating = false;
        }
    }

    private static @NotNull List<EffortOption> parseEffortOptions(@NotNull JsonObject model) {
        List<EffortOption> options = new ArrayList<>();
        if (!model.has("reasoning") || !model.get("reasoning").isJsonObject()) return options;
        JsonObject reasoning = model.getAsJsonObject("reasoning");
        if (!reasoning.has("efforts") || !reasoning.get("efforts").isJsonArray()) return options;
        for (JsonElement effortEl : reasoning.getAsJsonArray("efforts")) {
            if (!effortEl.isJsonObject()) continue;
            JsonObject effort = effortEl.getAsJsonObject();
            String id = effort.has("id") ? effort.get("id").getAsString() : "";
            if (id.isEmpty()) continue;
            String name = effort.has("name") ? effort.get("name").getAsString() : id;
            options.add(new EffortOption(id, name));
        }
        return options;
    }

    /** The deployment's configured default effort for one model ("" = none). */
    private static @NotNull String parseDefaultEffort(@NotNull JsonObject model) {
        if (!model.has("reasoning") || !model.get("reasoning").isJsonObject()) return "";
        JsonObject reasoning = model.getAsJsonObject("reasoning");
        if (reasoning.has("defaultEffort") && reasoning.get("defaultEffort").isJsonPrimitive()) {
            return reasoning.get("defaultEffort").getAsString();
        }
        return "";
    }

    /**
     * Rebuild the effort dropdown for the currently selected model, mirroring
     * the dsh web UI: a deployment default effort ({@code reasoning.defaultEffort})
     * replaces the "Default" entry (because an omitted effort would resolve to
     * it anyway and look like a no-op), otherwise the provider-default "Default"
     * entry is offered. The server's current effort wins the pre-selection,
     * then the deployment/stored default.
     */
    private void populateEffortForSelection(@Nullable String catalogEffort) {
        int index = modelBox.getSelectedIndex();
        List<EffortOption> options = index >= 0 && index < modelEffortOptions.size()
                ? modelEffortOptions.get(index) : List.of();
        String defaultEffort = index >= 0 && index < modelDefaultEfforts.size()
                ? modelDefaultEfforts.get(index) : "";
        boolean hasDefault = defaultEffort != null && !defaultEffort.isEmpty();
        String stored = DshSettingsState.getInstance().reasoningEffort;
        String preferred = firstNonBlank(catalogEffort, hasDefault ? defaultEffort : null, stored);
        boolean hasOptions = !options.isEmpty();
        effortUpdating = true;
        try {
            effortBox.removeAllItems();
            if (!hasDefault) effortBox.addItem(EffortOption.DEFAULT);
            for (EffortOption option : options) effortBox.addItem(option);
            EffortOption selected = findEffort(preferred, options);
            effortBox.setSelectedItem(selected != null ? selected
                    : (hasDefault && !options.isEmpty() ? options.getFirst() : EffortOption.DEFAULT));
            effortBox.setEnabled(hasOptions);
        } finally {
            effortUpdating = false;
        }
    }

    private static @Nullable EffortOption findEffort(@Nullable String id, @NotNull List<EffortOption> options) {
        if (id == null || id.isEmpty()) return null;
        for (EffortOption option : options) {
            if (id.equals(option.id())) return option;
        }
        return null;
    }

    private static @Nullable String firstNonBlank(@Nullable String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private void onModelChanged() {
        if (modelSelectionUpdating) return;
        int index = modelBox.getSelectedIndex();
        if (index < 0 || index >= modelProviders.size()) return;
        String model = modelBox.getItemAt(index);
        String provider = modelProviders.get(index);
        // Rebuild the effort choices for the new model (no RPC yet), then send
        // the complete selection (provider/model/effort) in one call.
        populateEffortForSelection(null);
        EffortOption effort = (EffortOption) effortBox.getSelectedItem();
        service.selectModel(provider, model, effort != null ? effort.id() : null);
    }

    /** The effort dropdown changed (only possible while the model supports it). */
    private void onEffortChanged() {
        if (effortUpdating) return;
        int index = modelBox.getSelectedIndex();
        if (index < 0 || index >= modelProviders.size()) return;
        String model = modelBox.getItemAt(index);
        String provider = modelProviders.get(index);
        EffortOption effort = (EffortOption) effortBox.getSelectedItem();
        service.selectModel(provider, model, effort != null ? effort.id() : null);
    }

    /** History dropdown selection: switch to that session (reload transcript). */
    private void onHistorySelected() {
        Object selected = historyBox.getSelectedItem();
        if (!(selected instanceof SessionEntry entry)) return;
        if (entry.sessionId.equals(activeSessionId)) return;
        clearTranscript();
        turnActive = false;
        updateSendButton();
        activeSessionId = entry.sessionId;
        setStatus("loading session...");
        service.switchSession(entry.sessionId);
    }

    private void populateSessionList(@NotNull JsonObject list) {
        historyBox.removeAllItems();
        if (!list.has("items") || !list.get("items").isJsonArray()) return;
        for (JsonElement itemEl : list.getAsJsonArray("items")) {
            JsonObject item = itemEl.isJsonObject() ? itemEl.getAsJsonObject() : null;
            if (item == null) continue;
            // Skip blank (conversation-not-started) sessions per dsh docs.
            if (item.has("blank") && item.get("blank").getAsBoolean()) continue;
            String sessionId = item.has("sessionId") ? item.get("sessionId").getAsString() : "";
            if (sessionId.isEmpty()) continue;
            String title = extractTitle(item);
            historyBox.addItem(new SessionEntry(sessionId, title));
        }
    }

    private static String extractTitle(@NotNull JsonObject item) {
        if (item.has("projections") && item.get("projections").isJsonObject()) {
            JsonObject projections = item.getAsJsonObject("projections");
            if (projections.has("values") && projections.get("values").isJsonObject()) {
                JsonObject values = projections.getAsJsonObject("values");
                if (values.has("title") && values.get("title").isJsonPrimitive()) {
                    String t = values.get("title").getAsString();
                    if (t != null && !t.isBlank()) return t;
                }
            }
        }
        String id = item.has("sessionId") ? item.get("sessionId").getAsString() : "";
        return id.isEmpty() ? "" : id.substring(0, Math.min(8, id.length()));
    }

    private void clearTranscript() {
        streamingAssistant = null;
        streamingReasoning = null;
        messageList.clear();
        messages.removeAll();
        messages.revalidate();
        messages.repaint();
    }

    /** The send/cancel button: sends a prompt when idle, cancels the turn while one runs. */
    private void onSendButtonClicked() {
        if (turnActive) {
            cancelCurrentTurn();
        } else {
            sendInput();
        }
    }

    private void cancelCurrentTurn() {
        setStatus("cancelling...");
        service.cancel();
    }

    private void updateSendButton() {
        sendButton.setIcon(turnActive ? CANCEL_ICON : AllIcons.Actions.Execute);
        sendButton.setToolTipText(turnActive ? "Cancel" : "Send");
    }

    private void sendInput() {
        if (turnActive) return; // the button is in cancel mode; don't stack prompts
        String text = input.getText();
        if (text == null || text.trim().isEmpty() || PLACEHOLDER.equals(text)) return;
        input.setText("");
        streamingAssistant = null;
        streamingReasoning = null;
        turnActive = true;
        updateSendButton();

        String cwd = Objects.requireNonNullElse(project.getBasePath(), ".");
        addMessage(ChatMessage.user("", text));
        addMessage(ChatMessage.status("", "agent is working..."));
        service.sendMessage(text, cwd);
    }

    private void newSession() {
        clearTranscript();
        turnActive = false;
        updateSendButton();
        setStatus("creating new session...");
        service.newSession(Objects.requireNonNullElse(project.getBasePath(), ".")).whenComplete((sessionId, error) -> {
            if (sessionId != null) {
                activeSessionId = sessionId;
                service.refreshSessionList();
            }
        });
    }

    private void onSandboxChanged() {
        Object selected = sandboxBox.getSelectedItem();
        if (selected == null) return;
        DshSettingsState settings = DshSettingsState.getInstance();
        String mode = (String) selected;
        if (!mode.equals(settings.sandboxMode)) {
            settings.sandboxMode = mode;
            settings.save();
            setStatus("switching sandbox mode to " + mode + "...");
            turnActive = false;
            updateSendButton();
            service.restartServer();
        }
    }

    private void addMessage(@NotNull ChatMessage message) {
        messageList.add(message);
        MessageBubble bubble = createBubble(message);
        messages.add(bubble);
        messages.revalidate();
        scrollToBottom();
    }

    private MessageBubble createBubble(@NotNull ChatMessage message) {
        return new MessageBubble(message, () -> onAllow(message), () -> onDeny(message), () -> {
            message.setCollapsed(!message.isCollapsed());
            updateMessage(message, false);
        });
    }

    private void onAllow(@NotNull ChatMessage message) {
        String rpcId = message.getRpcId();
        String approvalId = message.getApprovalId();
        if (rpcId != null && approvalId != null) {
            service.respondToApproval(rpcId, message.getSessionId(), approvalId, true);
            message.setApprovalState("allowed-once");
            removeMessage(message);
            setStatus("allowed: " + (message.getToolName() != null ? message.getToolName() : "tool"));
        }
    }

    private void onDeny(@NotNull ChatMessage message) {
        String rpcId = message.getRpcId();
        String approvalId = message.getApprovalId();
        if (rpcId != null && approvalId != null) {
            service.respondToApproval(rpcId, message.getSessionId(), approvalId, false);
            message.setApprovalState("rejected");
            removeMessage(message);
            setStatus("denied: " + (message.getToolName() != null ? message.getToolName() : "tool"));
        }
    }

    /** Rebuild a message bubble in place, optionally scrolling to the bottom. */
    private void updateMessage(@NotNull ChatMessage message) {
        updateMessage(message, true);
    }

    private void updateMessage(@NotNull ChatMessage message, boolean scrollToBottom) {
        int index = messageList.indexOf(message);
        if (index < 0) return;
        messages.remove(index);
        MessageBubble bubble = createBubble(message);
        messages.add(bubble, index);
        messages.revalidate();
        if (scrollToBottom) scrollToBottom();
    }

    /** Remove a message (e.g. an approval card after it is resolved). */
    private void removeMessage(@NotNull ChatMessage message) {
        int index = messageList.indexOf(message);
        if (index < 0) return;
        messageList.remove(index);
        messages.remove(index);
        messages.revalidate();
        messages.repaint();
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
            turnActive = true;
            setStatus("turn started");
        } else {
            turnActive = false;
            streamingAssistant = null;
            streamingReasoning = null;
            setStatus("turn finished" + (reason != null ? ": " + reason : ""));
        }
        updateSendButton();
    }

    @Override
    public void onAssistantMessage(@NotNull String sessionId, @NotNull String text,
                                   long uncachedInput, long cacheRead, long output) {
        ChatMessage target = streamingAssistant;
        if (target == null) {
            target = ChatMessage.assistant(sessionId, text);
            streamingAssistant = target;
            addMessage(target);
        }
        target.setTokenUsage(uncachedInput, cacheRead, output);
        updateMessage(target);
    }

    @Override
    public void onTokenUsage(@NotNull String sessionId, long uncachedInput, long cacheRead,
                             long cacheWrite, long output) {
        long total = uncachedInput + cacheRead + cacheWrite + output;
        tokenLabel.setText("tokens: " + MessageBubble.formatK(total));
    }

    @Override
    public void onTitleChanged(@NotNull String sessionId, @NotNull String title) {
        service.refreshSessionList();
    }

    @Override
    public void onSessionListRefreshed(@NotNull JsonObject list) {
        populateSessionList(list);
    }

    @Override
    public void onHistoryLoaded(@NotNull String sessionId, @NotNull JsonArray events) {
        clearTranscript();
        activeSessionId = sessionId;
        rebuildFromHistory(events);
        scrollToBottom();
        setStatus("session loaded");
    }

    /** Rebuild the transcript bubbles from a session.history event list. */
    private void rebuildFromHistory(@NotNull JsonArray events) {
        for (JsonElement entryEl : events) {
            JsonObject entry = entryEl.isJsonObject() ? entryEl.getAsJsonObject() : null;
            if (entry == null) continue;
            if (!entry.has("event") || !entry.get("event").isJsonObject()) continue;
            JsonObject event = entry.getAsJsonObject("event");
            String type = event.has("type") ? event.get("type").getAsString() : "";
            JsonObject data = event.has("data") && event.get("data").isJsonObject()
                    ? event.getAsJsonObject("data") : new JsonObject();
            switch (type) {
                case "user/message" -> {
                    String text = extractTextContent(data.get("content"));
                    if (!text.isEmpty()) addMessage(ChatMessage.user(activeSessionId, text));
                }
                case "assistant/message" -> {
                    JsonElement contentEl = data.has("message") && data.get("message").isJsonObject()
                            ? data.getAsJsonObject("message").get("content") : null;
                    rebuildAssistantContent(contentEl, data);
                }
                case "tool/call" -> {
                    String callId = data.has("callId") ? data.get("callId").getAsString() : "";
                    if (callId.isEmpty()) break;
                    ChatMessage existing = findToolCall(callId);
                    if (existing != null) {
                        if (data.has("name")) existing.setToolName(data.get("name").getAsString());
                        if (data.has("arguments")) existing.setArguments(data.get("arguments").getAsString());
                        updateMessage(existing, false);
                    } else {
                        String name = data.has("name") ? data.get("name").getAsString() : "tool";
                        String arguments = data.has("arguments") ? data.get("arguments").getAsString() : null;
                        ChatMessage card = ChatMessage.toolCall(activeSessionId, callId, name, arguments);
                        card.setToolRunning(false); // historical call is already finished
                        addMessage(card);
                    }
                }
                case "tool/result" -> {
                    // Bind the result to the already-rendered tool card (the
                    // tool-call event/block created it earlier in the log).
                    String callId = extractToolCallId(data);
                    if (callId == null || callId.isEmpty()) break;
                    ChatMessage card = findToolCall(callId);
                    if (card == null) {
                        card = ChatMessage.toolCall(activeSessionId, callId, "tool", null);
                        card.setToolRunning(false);
                        addMessage(card);
                    }
                    card.setToolRunning(false);
                    String result = extractToolResultText(data);
                    boolean isError = data.has("error") && data.get("error").isJsonObject()
                            || data.has("message") && data.get("message").isJsonObject()
                            && data.getAsJsonObject("message").has("isError")
                            && data.getAsJsonObject("message").get("isError").getAsBoolean();
                    if (isError) {
                        card.setToolError(result.isBlank() ? "tool execution failed" : result);
                    } else if (!result.isBlank()) {
                        card.appendResult(result);
                    }
                    updateMessage(card, false);
                }
                default -> {
                    // tool/result, turn markers, request/context: handled via the
                    // assistant/message content blocks or skipped.
                }
            }
        }
    }

    /** Render the content blocks of one assistant message (text + reasoning + tool-call). */
    private void rebuildAssistantContent(@Nullable JsonElement contentEl, @NotNull JsonObject data) {
        if (contentEl == null || !contentEl.isJsonArray()) return;
        StringBuilder text = new StringBuilder();
        JsonArray reasoningBlocks = new JsonArray();
        JsonArray toolCallBlocks = new JsonArray();
        for (JsonElement blockEl : contentEl.getAsJsonArray()) {
            JsonObject block = blockEl.isJsonObject() ? blockEl.getAsJsonObject() : null;
            if (block == null) continue;
            String blockType = block.has("type") ? block.get("type").getAsString() : "";
            switch (blockType) {
                case "text" -> {
                    if (block.has("text")) {
                        if (!text.isEmpty()) text.append('\n');
                        text.append(block.get("text").getAsString());
                    }
                }
                case "reasoning" -> reasoningBlocks.add(block);
                case "tool-call" -> toolCallBlocks.add(block);
                default -> {
                    // image, embedded-resource, etc. skip
                }
            }
        }

        if (!text.isEmpty() || reasoningBlocks.isEmpty() && toolCallBlocks.isEmpty()) {
            ChatMessage msg = ChatMessage.assistant(activeSessionId, text.toString());
            if (data.has("usage") && data.get("usage").isJsonObject()) {
                JsonObject usage = data.getAsJsonObject("usage");
                long uncached = usage.has("inputTokens") ? usage.get("inputTokens").getAsLong() : 0;
                long cacheRead = usage.has("cacheReadTokens") ? usage.get("cacheReadTokens").getAsLong() : 0;
                long output = usage.has("outputTokens") ? usage.get("outputTokens").getAsLong() : 0;
                msg.setTokenUsage(uncached, cacheRead, output);
            }
            addMessage(msg);
        }

        for (JsonElement rbEl : reasoningBlocks) {
            JsonObject rb = rbEl.getAsJsonObject();
            String rtext = rb.has("text") ? rb.get("text").getAsString() : "";
            if (!rtext.isEmpty()) addMessage(ChatMessage.reasoning(activeSessionId, rtext));
        }
        for (JsonElement tbEl : toolCallBlocks) {
            JsonObject tb = tbEl.getAsJsonObject();
            String callId = tb.has("id") ? tb.get("id").getAsString() : "";
            String name = tb.has("name") ? tb.get("name").getAsString() : "tool";
            String arguments = tb.has("arguments") ? tb.get("arguments").getAsString() : null;
            ChatMessage existing = callId.isEmpty() ? null : findToolCall(callId);
            if (existing != null) {
                if (!name.equals("tool")) existing.setToolName(name);
                if (arguments != null) existing.setArguments(arguments);
                updateMessage(existing, false);
            } else {
                ChatMessage card = ChatMessage.toolCall(activeSessionId, callId, name, arguments);
                card.setToolRunning(false);
                addMessage(card);
            }
        }
    }

    private static String extractTextContent(@Nullable JsonElement content) {
        if (content == null || !content.isJsonArray()) return "";
        StringBuilder sb = new StringBuilder();
        for (JsonElement blockEl : content.getAsJsonArray()) {
            JsonObject block = blockEl.isJsonObject() ? blockEl.getAsJsonObject() : null;
            if (block == null) continue;
            if (!block.has("type") || !"text".equals(block.get("type").getAsString())) continue;
            if (block.has("text")) {
                if (!sb.isEmpty()) sb.append('\n');
                sb.append(block.get("text").getAsString());
            }
        }
        return sb.toString();
    }

    /**
     * The tool call id referenced by a {@code tool/result} event. The event's
     * {@code message.content[0].toolCallId} is the authoritative id.
     */
    private static @Nullable String extractToolCallId(@NotNull JsonObject data) {
        if (data.has("message") && data.get("message").isJsonObject()) {
            JsonObject message = data.getAsJsonObject("message");
            if (message.has("content") && message.get("content").isJsonArray()) {
                JsonArray content = message.getAsJsonArray("content");
                if (!content.isEmpty() && content.get(0).isJsonObject()) {
                    JsonObject first = content.get(0).getAsJsonObject();
                    if (first.has("toolCallId")) return first.get("toolCallId").getAsString();
                }
            }
        }
        if (data.has("callId")) return data.get("callId").getAsString();
        return null;
    }

    /** Concatenated text of a {@code tool/result} event's result blocks. */
    private static @NotNull String extractToolResultText(@NotNull JsonObject data) {
        if (!data.has("message") || !data.get("message").isJsonObject()) return "";
        JsonObject message = data.getAsJsonObject("message");
        if (!message.has("content") || !message.get("content").isJsonArray()) return "";
        StringBuilder sb = new StringBuilder();
        for (JsonElement blockEl : message.getAsJsonArray("content")) {
            JsonObject block = blockEl.isJsonObject() ? blockEl.getAsJsonObject() : null;
            if (block == null) continue;
            if (!block.has("content") || !block.get("content").isJsonArray()) continue;
            for (JsonElement cbEl : block.getAsJsonArray("content")) {
                JsonObject cb = cbEl.isJsonObject() ? cbEl.getAsJsonObject() : null;
                if (cb != null && cb.has("type") && "text".equals(cb.get("type").getAsString())
                        && cb.has("text")) {
                    if (!sb.isEmpty()) sb.append('\n');
                    sb.append(cb.get("text").getAsString());
                }
            }
        }
        return sb.toString();
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
        removeMessage(msg);
        setStatus("permission " + outcome + (msg.getToolName() != null ? ": " + msg.getToolName() : ""));
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
        turnActive = false;
        updateSendButton();
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
        String s = status.toLowerCase();
        if (turnActive && (s.contains("disconnect") || s.contains("error") || s.contains("failed"))) {
            turnActive = false;
            updateSendButton();
        }
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

    /** One history-dropdown row: session identity + display title. */
    private record SessionEntry(String sessionId, String title) {

        @Override
        @NotNull
        public String toString() {
            return title.isEmpty() ? sessionId : title;
        }
    }

    /** One reasoning-effort dropdown row: catalog id + display name. */
    private record EffortOption(@NotNull String id, @NotNull String name) {

        /** "Default": do not send a reasoningEffort; the provider decides. */
        static final EffortOption DEFAULT = new EffortOption("", "Default");

        @Override
        @NotNull
        public String toString() {
            return name;
        }
    }

    /** Rounded input frame; the border color highlights while the input is focused. */
    public static final class InputFrame extends JPanel {
        private final int arc = JBUI.scale(12);
        private Color borderColor;
        private final JPanel bottomPanel;

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
