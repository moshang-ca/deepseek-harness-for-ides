package com.github.moshangca.dsh.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.github.moshangca.dsh.dsh.DshConfig;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@State(name = "DeepseekHarnessSettings", storages = @Storage("deepseek-harness-for-intellij.xml"))
@Service
public final class DshSettingsState implements PersistentStateComponent<DshSettingsState> {

    public String provider = DshConfig.DEFAULT_PROVIDER;
    public String model = DshConfig.DEFAULT_MODEL;
    public String apiKey = "";
    public int port = DshConfig.DEFAULT_PORT;
    /** Sandbox mode: read-only by default (safest); workspace-write / danger-full-access opt-in. */
    public String sandboxMode = DshConfig.SANDBOX_MODE_READ_ONLY;
    /** Selected reasoning effort id ("" = provider default); "off"/"high"/... as the model supports. */
    public String reasoningEffort = "";

    public static DshSettingsState getInstance() {
        return ApplicationManager.getApplication().getService(DshSettingsState.class);
    }

    public void save() {
        ApplicationManager.getApplication().saveSettings();
    }

    @Override
    public @Nullable DshSettingsState getState() {
        return this;
    }

    @Override
    public void loadState(@NotNull DshSettingsState state) {
        this.provider = state.provider;
        this.model = state.model;
        this.apiKey = state.apiKey;
        this.port = state.port;
        this.sandboxMode = state.sandboxMode;
        this.reasoningEffort = state.reasoningEffort;
    }
}
