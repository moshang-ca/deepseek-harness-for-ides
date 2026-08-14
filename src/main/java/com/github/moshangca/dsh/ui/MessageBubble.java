package com.github.moshangca.dsh.ui;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

/**
 * Renders one {@link ChatMessage} as an AI-Assistant-style bubble.
 * <ul>
 *   <li>USER — right-aligned, filled accent background</li>
 *   <li>ASSISTANT — left-aligned, plain</li>
 *   <li>REASONING — left-aligned, gray italic, collapsible</li>
 *   <li>TOOL_CALL — gray card: name + status, args block, result/error block</li>
 *   <li>PERMISSION — card with Allow/Deny buttons while pending, outcome badge once resolved</li>
 *   <li>STATUS — thin gray line</li>
 * </ul>
 */
public final class MessageBubble extends JPanel {

    private static final Color ERROR_COLOR = new JBColor(0xB4483C, 0xC0564B);
    private static final Color ALLOWED_COLOR = new JBColor(0x3F8F3F, 0x4E9A4E);
    private static final Color USER_BACKGROUND = new JBColor(0x2E6BB5, 0x3B6EA5);
    private static final Color MUTED_TEXT = UIManager.getColor("Label.disabledForeground");

    private final ChatMessage message;
    private final @NotNull Runnable onAllow;
    private final @NotNull Runnable onDeny;
    private final @NotNull Runnable onToggleCollapse;

    public MessageBubble(@NotNull ChatMessage message, @NotNull Runnable onAllow, @NotNull Runnable onDeny,
                         @NotNull Runnable onToggleCollapse) {
        super(new BorderLayout());
        this.message = message;
        this.onAllow = onAllow;
        this.onDeny = onDeny;
        this.onToggleCollapse = onToggleCollapse;

        setOpaque(false);
        setBorder(JBUI.Borders.empty(6, 8));

        switch (message.getKind()) {
            case USER -> add(buildUser());
            case ASSISTANT -> add(buildAssistant());
            case REASONING -> add(buildReasoning());
            case TOOL_CALL -> add(buildToolCall());
            case PERMISSION -> add(buildPermission());
            case STATUS -> add(buildStatus());
        }
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    private JComponent buildUser() {
        RoundedPanel bubble = new RoundedPanel(new BorderLayout(), USER_BACKGROUND);
        bubble.setBorder(JBUI.Borders.empty(8, 12));
        JBLabel label = wrapText(message.getText(), Color.WHITE);
        bubble.add(label, BorderLayout.CENTER);
        return bubble;
    }

    private JComponent buildAssistant() {
        JBLabel label = wrapText(message.getText());
        label.setBorder(JBUI.Borders.empty(4, 2));
        return label;
    }

    private JComponent buildReasoning() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setOpaque(false);
        panel.setBorder(JBUI.Borders.empty(2, 2));

        JLabel header = new JLabel((message.isCollapsed() ? "▸ " : "▾ ") + "thinking");
        header.setForeground(MUTED_TEXT);
        header.setFont(header.getFont().deriveFont(Font.ITALIC, header.getFont().getSize() - 1));
        header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        header.setBorder(JBUI.Borders.empty(2, 0));
        header.setAlignmentX(Component.LEFT_ALIGNMENT);
        header.setMaximumSize(new Dimension(Integer.MAX_VALUE, header.getPreferredSize().height));
        header.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                onToggleCollapse.run();
            }
        });
        panel.add(header);

        if (!message.isCollapsed()) {
            JBLabel content = wrapText(message.getText());
            content.setForeground(MUTED_TEXT);
            content.setFont(content.getFont().deriveFont(Font.ITALIC, content.getFont().getSize() - 1));
            content.setBorder(JBUI.Borders.empty(0, 6, 2, 0));
            content.setAlignmentX(Component.LEFT_ALIGNMENT);
            content.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
            panel.add(content);
        }
        return panel;
    }

    private JComponent buildToolCall() {
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setOpaque(true);
        card.setBackground(UIManager.getColor("Panel.background").darker());
        card.setBorder(JBUI.Borders.empty(6, 8));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.setAlignmentX(Component.LEFT_ALIGNMENT);
        header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        String name = message.getToolName() != null ? message.getToolName() : "tool";
        JBLabel title = new JBLabel((message.isCollapsed() ? "▸ " : "▾ ") + "\uD83D\uDD27 " + name);
        title.setFont(title.getFont().deriveFont(Font.PLAIN, title.getFont().getSize() - 1));
        header.add(title, BorderLayout.WEST);
        String status = message.isToolRunning() ? "running"
                : message.getToolError() != null ? "error" : "";
        if (!status.isEmpty()) {
            JBLabel badge = new JBLabel(status);
            badge.setForeground("error".equals(status) ? ERROR_COLOR : MUTED_TEXT);
            header.add(badge, BorderLayout.EAST);
        }
        header.setMaximumSize(new Dimension(Integer.MAX_VALUE, header.getPreferredSize().height));
        header.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                onToggleCollapse.run();
            }
        });
        card.add(header);

        if (!message.isCollapsed()) {
            String args = prettyJson(message.getArguments());
            if (!args.isBlank()) card.add(monospaceBlock("args", args, false));
            String result = message.getResultText();
            String error = message.getToolError();
            if (error != null) {
                card.add(monospaceBlock("error", error, true));
            } else if (!result.isBlank()) {
                card.add(monospaceBlock("result", result, false));
            }
        }
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, card.getPreferredSize().height));
        return card;
    }

    private JComponent monospaceBlock(String title, String text, boolean error) {
        JPanel block = new JPanel(new BorderLayout());
        block.setOpaque(false);
        block.setBorder(JBUI.Borders.empty(4, 0, 0, 0));
        block.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel label = new JLabel(title);
        label.setFont(label.getFont().deriveFont(Font.BOLD, label.getFont().getSize() - 2));
        label.setForeground(UIManager.getColor("Label.disabledForeground"));
        block.add(label, BorderLayout.NORTH);

        JTextArea area = new JTextArea(text);
        area.setEditable(false);
        area.setOpaque(true);
        area.setBackground(UIManager.getColor("Panel.background"));
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setBorder(JBUI.Borders.empty(4, 4));
        if (error) area.setForeground(ERROR_COLOR);

        JScrollPane scroll = new JBScrollPane(area,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(JBUI.Borders.empty());
        scroll.setPreferredSize(new Dimension(200, JBUI.scale(120)));
        block.add(scroll, BorderLayout.CENTER);
        block.setMaximumSize(new Dimension(Integer.MAX_VALUE, block.getPreferredSize().height));
        return block;
    }

    private JComponent buildPermission() {
        JPanel card = new JPanel(new BorderLayout());
        card.setOpaque(true);
        card.setBackground(UIManager.getColor("Panel.background").darker());
        card.setBorder(JBUI.Borders.empty(8));

        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        JBLabel label = new JBLabel(message.getText());
        label.setForeground(UIManager.getColor("Label.foreground"));
        top.add(label, BorderLayout.WEST);

        String state = message.getApprovalState();
        if (state != null) {
            JBLabel badge = new JBLabel(state);
            badge.setForeground("allowed-once".equals(state) ? ALLOWED_COLOR : MUTED_TEXT);
            badge.setBorder(JBUI.Borders.empty(0, 8, 0, 0));
            top.add(badge, BorderLayout.EAST);
            card.add(top, BorderLayout.CENTER);
            return card;
        }

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        buttons.setOpaque(false);
        JButton allow = new JButton("Allow");
        allow.addActionListener(e -> onAllow.run());
        JButton deny = new JButton("Deny");
        deny.addActionListener(e -> onDeny.run());
        buttons.add(allow);
        buttons.add(deny);

        card.add(top, BorderLayout.CENTER);
        card.add(buttons, BorderLayout.SOUTH);
        return card;
    }

    private JComponent buildStatus() {
        JBLabel label = new JBLabel("<html><font color=\"" + toHex(MUTED_TEXT) + "\">" + escapeHtml(message.getText()) + "</font></html>");
        label.setBorder(JBUI.Borders.empty(2, 2));
        return label;
    }

    private static String toHex(Color color) {
        return String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
    }

    /** A panel that paints its background as a rounded rectangle. */
    private static final class RoundedPanel extends JPanel {
        private final int arc;

        RoundedPanel(LayoutManager layout, Color background) {
            super(layout);
            this.arc = JBUI.scale(14);
            setOpaque(false);
            setBackground(background);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(getBackground());
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    private static String prettyJson(String raw) {
        try {
            return new GsonBuilder().setPrettyPrinting().create().toJson(JsonParser.parseString(raw));
        } catch (Exception e) {
            return raw;
        }
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\n", "<br>");
    }

    /** A label that wraps its HTML text to the available width. */
    private static JBLabel wrapText(String text) {
        return wrapText(text, null);
    }

    private static JBLabel wrapText(String text, @Nullable Color color) {
        String body = color != null
                ? "<font color=\"" + toHex(color) + "\">" + escapeHtml(text) + "</font>"
                : escapeHtml(text);
        JBLabel label = new JBLabel("<html>" + body + "</html>");
        label.setOpaque(false);
        return label;
    }
}
