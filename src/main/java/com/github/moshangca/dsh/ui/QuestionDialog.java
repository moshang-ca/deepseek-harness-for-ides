package com.github.moshangca.dsh.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Collects answers to a {@code question/requested} batch. Each question renders
 * as radio buttons (single-select), checkboxes (multi-select), or a free-text
 * field when it has no options.
 */
public final class QuestionDialog extends DialogWrapper {

    private final JsonArray questions;
    private final List<QuestionControls> controls = new ArrayList<>();

    public QuestionDialog(@Nullable Project project, @NotNull JsonArray questions) {
        super(project);
        this.questions = questions;
        setTitle("DeepSeek Harness");
        setOKButtonText("Submit");
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel root = new JPanel();
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        for (JsonElement el : questions) {
            QuestionControls qc = new QuestionControls(el.getAsJsonObject());
            controls.add(qc);
            root.add(qc.panel);
            root.add(Box.createVerticalStrut(8));
        }
        JScrollPane scroll = new JBScrollPane(root);
        scroll.setBorder(JBUI.Borders.empty());
        scroll.setPreferredSize(new Dimension(520, Math.min(420, 60 + questions.size() * 120)));
        return scroll;
    }

    public @NotNull JsonArray getAnswers() {
        JsonArray answers = new JsonArray();
        for (QuestionControls qc : controls) {
            JsonObject answer = new JsonObject();
            answer.addProperty("id", qc.id);
            JsonArray selected = new JsonArray();
            for (String label : qc.selectedLabels()) selected.add(label);
            answer.add("selected", selected);
            String custom = qc.customText();
            if (custom != null && !custom.isBlank()) answer.addProperty("custom", custom);
            answers.add(answer);
        }
        return answers;
    }

    private static final class QuestionControls {

        final String id;
        final JPanel panel = new JPanel();

        private final AbstractButton[] options;
        private final boolean multiSelect;
        private final JTextField customField;

        QuestionControls(JsonObject question) {
            this.id = question.has("id") ? question.get("id").getAsString() : "";
            this.multiSelect = question.has("multiSelect") && question.get("multiSelect").getAsBoolean();

            panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
            panel.setBorder(JBUI.Borders.empty(8, 10, 8, 10));

            if (question.has("header")) {
                JLabel header = new JLabel(question.get("header").getAsString());
                header.setFont(header.getFont().deriveFont(Font.BOLD));
                panel.add(header);
            }
            JLabel label = new JLabel("<html><b>" + escape(question.get("question").getAsString()) + "</b></html>");
            panel.add(label);
            if (question.has("detail")) {
                JLabel detail = new JLabel("<html><i>" + escape(question.get("detail").getAsString()) + "</i></html>");
                detail.setForeground(UIManager.getColor("Label.disabledForeground"));
                panel.add(detail);
            }
            panel.add(Box.createVerticalStrut(4));

            JsonArray optionArray = question.has("options") && question.get("options").isJsonArray()
                    ? question.getAsJsonArray("options") : new JsonArray();
            if (!optionArray.isEmpty()) {
                this.options = new AbstractButton[optionArray.size()];
                ButtonGroup group = multiSelect ? null : new ButtonGroup();
                for (int i = 0; i < optionArray.size(); i++) {
                    JsonObject option = optionArray.get(i).getAsJsonObject();
                    String text = option.has("label") ? option.get("label").getAsString() : "";
                    String description = option.has("description") ? option.get("description").getAsString() : null;
                    AbstractButton button = multiSelect
                            ? new JCheckBox(description != null ? text + " - " + description : text)
                            : new JRadioButton(description != null ? text + " - " + description : text);
                    if (group != null) group.add(button);
                    this.options[i] = button;
                    panel.add(button);
                }
                this.customField = multiSelect ? new JTextField("Other...", 30) : null;
                if (customField != null) panel.add(customField);
            } else {
                this.options = new AbstractButton[0];
                this.customField = new JTextField(30);
                panel.add(customField);
            }
        }

        List<String> selectedLabels() {
            List<String> labels = new ArrayList<>();
            for (AbstractButton b : options) {
                if (b.isSelected()) labels.add(labelOf(b));
            }
            return labels;
        }

        /** The answer must carry the option label verbatim, without the description suffix. */
        private static String labelOf(AbstractButton b) {
            String text = b.getText();
            int sep = text.indexOf(" - ");
            return sep >= 0 ? text.substring(0, sep) : text;
        }

        @Nullable
        String customText() {
            if (customField == null) return null;
            String text = customField.getText();
            // Skip the placeholder ("Other...") and empty input.
            if (text.isBlank() || "Other...".equals(text)) return null;
            return text;
        }
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
