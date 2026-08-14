package com.github.moshangca.deepseekharnessforintellij.ui;

import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;

/**
 * Renders one {@link ChatMessage} as an AI-Assistant-style bubble.
 * <ul>
 *   <li>USER — right-aligned, filled accent background</li>
 *   <li>ASSISTANT — left-aligned, plain</li>
 *   <li>REASONING — left-aligned, gray italic, collapsible</li>
 *   <li>TOOL_CALL — gray card with monospace content</li>
 *   <li>PERMISSION — card with Allow/Deny buttons</li>
 *   <li>STATUS — thin gray line</li>
 * </ul>
 */
public final class MessageBubble extends JPanel {

    private final ChatMessage message;
    private final @NotNull Runnable onAllow;
    private final @NotNull Runnable onDeny;

    public MessageBubble(@NotNull ChatMessage message, @NotNull Runnable onAllow, @NotNull Runnable onDeny) {
        super(new BorderLayout());
        this.message = message;
        this.onAllow = onAllow;
        this.onDeny = onDeny;

        setOpaque(false);
        setBorder(JBUI.Borders.empty(4, 8));

        switch (message.getKind()) {
            case USER -> add(buildUser(), BorderLayout.EAST);
            case ASSISTANT -> add(buildAssistant(), BorderLayout.WEST);
            case REASONING -> add(buildReasoning(), BorderLayout.WEST);
            case TOOL_CALL -> add(buildToolCall(), BorderLayout.WEST);
            case PERMISSION -> add(buildPermission(), BorderLayout.WEST);
            case STATUS -> add(buildStatus(), BorderLayout.WEST);
        }
    }

    private JComponent buildUser() {
        JPanel bubble = new JPanel(new BorderLayout());
        bubble.setOpaque(true);
        bubble.setBackground(new Color(0x3B6FCF)); // JetBrains-ish blue
        bubble.setBorder(JBUI.Borders.empty(8, 12));
        JBLabel label = new JBLabel(message.getText());
        label.setForeground(Color.WHITE);
        label.setBorder(JBUI.Borders.empty());
        bubble.add(label, BorderLayout.CENTER);
        bubble.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        return bubble;
    }

    private JComponent buildAssistant() {
        JBLabel label = new JBLabel(message.getText());
        label.setBorder(JBUI.Borders.empty(4, 2));
        label.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        return label;
    }

    private JComponent buildReasoning() {
        JBLabel label = new JBLabel("<html><i><font color=\"#808080\">" + escapeHtml(message.getText()) + "</font></i></html>");
        label.setBorder(JBUI.Borders.empty(4, 2));
        label.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        return label;
    }

    private JComponent buildToolCall() {
        JPanel card = new JPanel(new BorderLayout());
        card.setOpaque(true);
        card.setBackground(UIManager.getColor("Panel.background").darker());
        card.setBorder(JBUI.Borders.empty(6, 8));
        JBLabel title = new JBLabel("\uD83D\uDD27 " + message.getText());
        title.setFont(title.getFont().deriveFont(Font.PLAIN, title.getFont().getSize() - 1));
        title.setForeground(UIManager.getColor("Label.foreground"));
        card.add(title, BorderLayout.NORTH);
        return card;
    }

    private JComponent buildPermission() {
        JPanel card = new JPanel(new BorderLayout());
        card.setOpaque(true);
        card.setBackground(UIManager.getColor("Panel.background").darker());
        card.setBorder(JBUI.Borders.empty(8));

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        top.setOpaque(false);
        JBLabel label = new JBLabel(message.getText());
        label.setForeground(UIManager.getColor("Label.foreground"));
        top.add(label);

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
        JBLabel label = new JBLabel("<html><font color=\"#808080\">" + escapeHtml(message.getText()) + "</font></html>");
        label.setBorder(JBUI.Borders.empty(2, 2));
        return label;
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\n", "<br>").replace(" ", "&nbsp;");
    }
}
