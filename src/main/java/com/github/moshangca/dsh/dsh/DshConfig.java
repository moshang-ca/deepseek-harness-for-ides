package com.github.moshangca.dsh.dsh;

/**
 * Configuration for the dsh web server that the plugin launches.
 *
 * <p>Instead of hand-generating a cordis.yml, the plugin installs the
 * {@code @deepseek-ai/dsh} npm package (the full harness) and launches it with
 * the {@code web} profile. All tuning goes through environment variables that
 * the web profile reads at boot:</p>
 * <ul>
 *   <li>{@code DSH_PERMISSION_MODE} — sandbox mode (read-only / workspace-write / danger-full-access)</li>
 *   <li>{@code DEEPSEEK_API_KEY} — the LLM credential</li>
 * </ul>
 */
public final class DshConfig {

    private DshConfig() {
    }

    public static final String NPM_DIST_TAG = "next";

    /** Default DeepSeek provider route id used by dsh. */
    public static final String DEFAULT_PROVIDER = "deepseek-official";

    public static final String DEFAULT_MODEL = "deepseek-v4-pro";

    public static final String SANDBOX_MODE_READ_ONLY = "read-only";

    public static final String SANDBOX_MODE_WORKSPACE = "workspace-write";

    public static final String SANDBOX_MODE_FULL = "danger-full-access";

    public static final String[] SANDBOX_MODES = {
            SANDBOX_MODE_READ_ONLY,
            SANDBOX_MODE_WORKSPACE,
            SANDBOX_MODE_FULL,
    };

    public static final String DSH_PERMISSION_MODE_ENV = "DSH_PERMISSION_MODE";

    public static final int DEFAULT_PORT = 3080;

    public static final String DSH_PACKAGE = "@deepseek-ai/dsh@" + NPM_DIST_TAG;

    /** npm packages the plugin installs into the dsh work directory. */
    public static final String[] NPM_PACKAGES = {DSH_PACKAGE};

    /** The dsh bin entry (installed into node_modules/.bin). */
    public static final String DSH_BIN = "dsh";
}
