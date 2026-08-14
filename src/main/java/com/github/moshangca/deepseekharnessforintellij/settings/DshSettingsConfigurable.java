package com.github.moshangca.deepseekharnessforintellij.settings;

import com.github.moshangca.deepseekharnessforintellij.MyBundle;
import com.github.moshangca.deepseekharnessforintellij.dsh.DshConfig;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

public final class DshSettingsConfigurable implements Configurable {

    private final DshSettingsState state = DshSettingsState.getInstance();

    private JTextField providerField;
    private JTextField modelField;
    private JPasswordField apiKeyField;
    private JTextField portField;
    private JComboBox<String> sandboxModeBox;

    @Nls(capitalization = Nls.Capitalization.Title)
    @Override
    public String getDisplayName() {
        return MyBundle.message("settings.displayName");
    }

    @Override
    public @Nullable JComponent createComponent() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = JBUI.insets(4, 8, 4, 8);
        c.anchor = GridBagConstraints.WEST;

        providerField = new JTextField(state.provider, 20);
        modelField = new JTextField(state.model, 20);
        apiKeyField = new JPasswordField(state.apiKey, 20);
        portField = new JTextField(String.valueOf(state.port), 6);
        sandboxModeBox = new JComboBox<>(new String[]{
                DshConfig.SANDBOX_MODE_WORKSPACE,
                DshConfig.SANDBOX_MODE_FULL,
        });
        sandboxModeBox.setSelectedItem(state.sandboxMode);
        sandboxModeBox.setPrototypeDisplayValue("danger-full-access");

        int row = 0;
        addRow(panel, c, row++, MyBundle.message("settings.provider.label"), providerField);
        addRow(panel, c, row++, MyBundle.message("settings.model.label"), modelField);
        addRow(panel, c, row++, MyBundle.message("settings.apiKey.label"), apiKeyField);
        addRow(panel, c, row++, MyBundle.message("settings.port.label"), portField);
        addRow(panel, c, row++, MyBundle.message("settings.sandboxMode.label"), sandboxModeBox);

        // Note below the form, full width.
        c.gridx = 0;
        c.gridy = row;
        c.gridwidth = 2;
        c.fill = GridBagConstraints.HORIZONTAL;
        JLabel note = new JLabel(MyBundle.message("settings.sandboxMode.note"));
        note.setForeground(UIManager.getColor("Label.disabledForeground"));
        note.setBorder(JBUI.Borders.emptyTop(4));
        panel.add(note, c);

        return panel;
    }

    private void addRow(JPanel panel, GridBagConstraints c, int row, String label, JComponent field) {
        c.gridx = 0;
        c.gridy = row;
        c.gridwidth = 1;
        c.fill = GridBagConstraints.NONE;
        c.weightx = 0;
        panel.add(new JLabel(label), c);

        c.gridx = 1;
        c.gridwidth = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        panel.add(field, c);
    }

    @Override
    public boolean isModified() {
        return !providerField.getText().equals(state.provider)
                || !modelField.getText().equals(state.model)
                || !String.valueOf(apiKeyField.getPassword()).equals(state.apiKey)
                || !portField.getText().equals(String.valueOf(state.port))
                || !sandboxModeBox.getSelectedItem().equals(state.sandboxMode);
    }

    @Override
    public void apply() throws ConfigurationException {
        state.provider = providerField.getText().trim();
        state.model = modelField.getText().trim();
        state.apiKey = String.valueOf(apiKeyField.getPassword()).trim();
        try {
            int parsed = Integer.parseInt(portField.getText().trim());
            if (parsed < 1 || parsed > 65535) {
                throw new NumberFormatException("out of range");
            }
            state.port = parsed;
        } catch (NumberFormatException e) {
            throw new ConfigurationException("Port must be a number between 1 and 65535.");
        }
        state.sandboxMode = (String) sandboxModeBox.getSelectedItem();
    }

    @Override
    public void reset() {
        providerField.setText(state.provider);
        modelField.setText(state.model);
        apiKeyField.setText(state.apiKey);
        portField.setText(String.valueOf(state.port));
        sandboxModeBox.setSelectedItem(state.sandboxMode);
    }
}
