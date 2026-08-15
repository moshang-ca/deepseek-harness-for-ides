package com.github.moshangca.dsh.settings;

import com.github.moshangca.dsh.DshBundle;
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
    private JPasswordField apiKeyField;
    private JTextField portField;

    @Nls(capitalization = Nls.Capitalization.Title)
    @Override
    public String getDisplayName() {
        return DshBundle.message("settings.displayName");
    }

    @Override
    public @Nullable JComponent createComponent() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = JBUI.insets(4, 8);
        c.fill = GridBagConstraints.HORIZONTAL;

        providerField = new JTextField(state.provider, 20);
        apiKeyField = new JPasswordField(state.apiKey, 20);
        portField = new JTextField(String.valueOf(state.port), 6);

        int row = 0;
        // 添加标签-输入框行
        addRow(panel, c, row++, DshBundle.message("settings.provider.label"), providerField);
        addRow(panel, c, row++, DshBundle.message("settings.apiKey.label"), apiKeyField);
        addRow(panel, c, row++, DshBundle.message("settings.port.label"), portField);

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
                || !String.valueOf(apiKeyField.getPassword()).equals(state.apiKey)
                || !portField.getText().equals(String.valueOf(state.port));
    }

    @Override
    public void apply() throws ConfigurationException {
        state.provider = providerField.getText().trim();
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
    }

    @Override
    public void reset() {
        providerField.setText(state.provider);
        apiKeyField.setText(state.apiKey);
        portField.setText(String.valueOf(state.port));
    }
}
