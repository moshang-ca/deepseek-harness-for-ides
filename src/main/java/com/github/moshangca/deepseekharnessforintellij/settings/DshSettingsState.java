package com.github.moshangca.deepseekharnessforintellij.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.github.moshangca.deepseekharnessforintellij.dsh.DshConfig;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@State(name = "DeepseekHarnessSettings", storages = @Storage("deepseek-harness-for-intellij.xml"))
@Service
public final class DshSettingsState implements PersistentStateComponent<DshSettingsState> {

    public String provider = DshConfig.DEFAULT_PROVIDER;
    public String model = DshConfig.DEFAULT_MODEL;
    public String apiKey = "";
    public int port = DshConfig.DEFAULT_PORT;
    /**
     * Sandbox mode: "workspace-write" (safe default) or "danger-full-access"
     * (allows cross-project access). On Windows the workspace-write runner
     * (restricted-token ACL) makes Git Bash crash at startup
     * ("couldn't create signal pipe, Win32 error 5"), so Windows defaults to
     * danger-full-access; Linux/macOS keep the safe workspace-write default.
     */
    public String sandboxMode = defaultSandboxMode();

    private static String defaultSandboxMode() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("win") ? DshConfig.SANDBOX_MODE_FULL : DshConfig.SANDBOX_MODE_WORKSPACE;
    }

    public static DshSettingsState getInstance() {
        return ApplicationManager.getApplication().getService(DshSettingsState.class);
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
    }
}
