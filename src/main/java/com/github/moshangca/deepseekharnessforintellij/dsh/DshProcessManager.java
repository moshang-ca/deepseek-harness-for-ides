package com.github.moshangca.deepseekharnessforintellij.dsh;

import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Manages the lifecycle of the dsh web server child process.
 *
 * <p>The server is launched as {@code node <workdir>/node_modules/@deepseek-ai/
 * dsh/lib/bin.js web --port <port>} from the plugin's dsh work directory. The
 * work directory is created lazily and holds the {@code npm install} of the dsh
 * packages. The web profile exposes the HTTP API on {@code 127.0.0.1:<port>}
 * (default {@link DshConfig#DEFAULT_PORT}).</p>
 *
 * <p>stdout/stderr are drained into the IntelliJ log; the API is reached over
 * HTTP by {@code DshApiClient} / {@code DshApiEvents}.</p>
 */
public final class DshProcessManager {

    private static final Logger LOG = Logger.getInstance(DshProcessManager.class);

    public interface Listener {
        /** The dsh process exited on its own (not via {@link #stop()}). */
        void onExited(int exitCode);
    }

    private final Path workDir;
    private final int port;
    private final Path nodeExe;
    private final Path npmCmd;

    private @Nullable Process process;
    private @Nullable Listener listener;

    /**
     * @param workDir the plugin-owned dsh work directory (created on demand)
     * @param port    the HTTP port the web server binds to
     */
    public DshProcessManager(@NotNull Path workDir, int port) {
        this.workDir = workDir;
        this.port = port;
        this.nodeExe = findExecutable("node");
        this.npmCmd = findNpm();
    }

    public boolean isNodeAvailable() {
        return nodeExe != null && npmCmd != null;
    }

    /**
     * Ensure the work directory exists and the dsh packages are installed.
     * The first call performs an {@code npm install}; later calls are no-ops
     * when the expected dsh bin is present.
     *
     * <p>Checks the actual dsh bin rather than just the presence of
     * {@code node_modules}: a stale cache from an older plugin version (e.g.
     * the acp-demo bundle) has a node_modules directory but no
     * {@code @deepseek-ai/dsh}, so only a bin check forces a fresh install.</p>
     *
     * @return true on success
     */
    public boolean prepare() {
        try {
            Files.createDirectories(workDir);
            if (isInstalled()) {
                return true;
            }
            return npmInstall();
        } catch (IOException e) {
            LOG.warn("failed to prepare dsh work directory " + workDir, e);
            return false;
        }
    }

    /** Whether the expected dsh bin is present in the work directory. */
    public boolean isInstalled() {
        return Files.exists(dshBin());
    }

    /** The resolved dsh bin path in the work directory. */
    private @NotNull Path dshBin() {
        return workDir.resolve("node_modules/@deepseek-ai/dsh/lib/bin.js");
    }

    /**
     * Start the dsh web server process.
     *
     * @param provider    the configured provider route (informational; dsh reads
     *                    model/provider from its own config, env, or defaults)
     * @param model       the configured model (informational)
     * @param sandboxMode one of {@link DshConfig#SANDBOX_MODE_WORKSPACE} or
     *                    {@link DshConfig#SANDBOX_MODE_FULL}
     * @param apiKey      the DEEPSEEK_API_KEY, or null to inherit the ambient environment
     * @return the started process, or null on failure
     */
    public @Nullable Process start(@NotNull String provider, @NotNull String model,
                                   @NotNull String sandboxMode, @Nullable String apiKey) {
        if (process != null) {
            LOG.warn("dsh process already running");
            return process;
        }
        if (nodeExe == null || npmCmd == null) {
            LOG.error("node/npm not available");
            return null;
        }
        try {
            Path bin = dshBin();
            if (!Files.exists(bin)) {
                LOG.error("dsh not installed at " + bin);
                return null;
            }

            GeneralCommandLine commandLine = new GeneralCommandLine()
                    .withExePath(nodeExe.toString())
                    .withParameters(bin.toString(), "web", "--port", String.valueOf(port))
                    .withWorkDirectory(workDir.toFile())
                    .withCharset(StandardCharsets.UTF_8);
            // The web profile reads these at boot. sandbox mode via
            // DSH_PERMISSION_MODE; API key via DEEPSEEK_API_KEY. Git Bash dirs
            // are injected so the harness can spawn `bash -c ...`.
            commandLine.getEnvironment().put(DshConfig.DSH_PERMISSION_MODE_ENV, sandboxMode);
            String path = System.getenv("PATH");
            if (path != null) {
                commandLine.getEnvironment().put("PATH", augmentPathForBash(path));
            }
            if (apiKey != null && !apiKey.isBlank()) {
                commandLine.getEnvironment().put(DshConfig.DEEPSEEK_API_KEY_ENV, apiKey);
            }

            Process child = commandLine.createProcess();
            process = child;

            // Drain both streams so the child never blocks writing diagnostics.
            drain(child.getInputStream(), "stdout");
            drain(child.getErrorStream(), "stderr");

            Thread waiter = new Thread(() -> {
                try {
                    int exit = child.waitFor();
                    if (process != null && process == child) {
                        process = null;
                        Listener l = listener;
                        if (l != null) l.onExited(exit);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "dsh-exit-waiter");
            waiter.setDaemon(true);
            waiter.start();

            LOG.info("dsh web server starting (pid=" + child.pid() + ", port=" + port + ")");
            return child;
        } catch (Exception e) {
            LOG.error("failed to start dsh web server", e);
            return null;
        }
    }

    public int getPort() {
        return port;
    }

    /**
     * Poll {@code POST /api/host.describe} until the web server answers or the
     * timeout elapses. Use after {@link #start} to know when the API is live.
     *
     * @param timeoutMs maximum wait in milliseconds
     * @return true when the API responded successfully
     */
    public boolean awaitReady(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Process current = process;
            if (current == null || !current.isAlive()) return false;
            try {
                java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
                String body = "{\"type\":\"client-request\",\"rpcId\":\"ready\",\"method\":\"host.describe\",\"payload\":{}}";
                java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create("http://127.0.0.1:" + port + "/api/host.describe"))
                        .timeout(java.time.Duration.ofSeconds(3))
                        .header("Content-Type", "application/json")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                        .build();
                java.net.http.HttpResponse<String> resp = client.send(req,
                        java.net.http.HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200 && resp.body().contains("\"ok\":true")) {
                    return true;
                }
            } catch (Exception e) {
                // server not up yet
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    public void stop() {
        Process current = process;
        if (current == null) return;
        process = null;
        try {
            current.destroy();
            if (!current.waitFor(5, TimeUnit.SECONDS)) {
                current.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public boolean isRunning() {
        return process != null && process.isAlive();
    }

    public void setListener(@Nullable Listener listener) {
        this.listener = listener;
    }

    @NotNull
    public Path getWorkDir() {
        return workDir;
    }

    private void drain(@NotNull java.io.InputStream in, @NotNull String label) {
        Thread t = new Thread(() -> {
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    LOG.info("[dsh " + label + "] " + line);
                }
            } catch (IOException e) {
                // stream closed during teardown is expected
            }
        }, "dsh-drain-" + label);
        t.setDaemon(true);
        t.start();
    }

    private boolean npmInstall() {
        try {
            Path nodeModules = workDir.resolve("node_modules");
            if (Files.exists(nodeModules)) {
                deleteRecursively(nodeModules);
            }
            Files.deleteIfExists(workDir.resolve("package-lock.json"));
            Files.deleteIfExists(workDir.resolve("package.json"));

            GeneralCommandLine commandLine = new GeneralCommandLine()
                    .withExePath(npmCmd.toString())
                    .withParameters("install", "--no-audit", "--no-fund", "--loglevel=error",
                            "--no-save", "--prefix", workDir.toString())
                    .withParameters(DshConfig.NPM_PACKAGES)
                    .withWorkDirectory(workDir.toFile())
                    .withCharset(StandardCharsets.UTF_8);
            com.intellij.execution.process.CapturingProcessHandler handler =
                    new com.intellij.execution.process.CapturingProcessHandler(commandLine);
            com.intellij.execution.process.ProcessOutput output = handler.runProcess(600_000);
            if (output.getExitCode() != 0) {
                LOG.error("npm install of dsh failed with exit code " + output.getExitCode()
                        + "\n" + output.getStderr());
                return false;
            }
            return isInstalled();
        } catch (Exception e) {
            LOG.error("npm install of dsh failed", e);
            return false;
        }
    }

    private static void deleteRecursively(@NotNull Path dir) throws IOException {
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            LOG.warn("failed to delete " + p + ": " + e.getMessage());
                        }
                    });
        }
    }

    private static @Nullable Path findExecutable(@NotNull String name) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) return null;
        String suffix = System.getProperty("os.name").toLowerCase().contains("win") ? ".exe" : "";
        for (String dir : pathEnv.split(java.io.File.pathSeparator)) {
            if (dir.isBlank()) continue;
            Path candidate = Path.of(dir, name + suffix);
            if (Files.isExecutable(candidate)) return candidate;
        }
        return null;
    }

    private static @Nullable Path findNpm() {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) return null;
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        for (String dir : pathEnv.split(java.io.File.pathSeparator)) {
            if (dir.isBlank()) continue;
            for (String candidate : windows
                    ? new String[]{"npm.cmd", "npm"}
                    : new String[]{"npm"}) {
                Path p = Path.of(dir, candidate);
                if (Files.exists(p)) return p;
            }
        }
        return null;
    }

    /**
     * Prepend Git Bash directories to the given PATH so the dsh child can spawn
     * {@code bash} and the usual Unix utilities. No-op when bash is already on
     * the PATH or no Git installation is found.
     */
    private static @NotNull String augmentPathForBash(@NotNull String path) {
        String[] gitRoots = {
                System.getenv("ProgramFiles") + "\\Git",
                "C:\\Program Files\\Git",
                "C:\\Program Files (x86)\\Git",
        };
        StringBuilder extra = new StringBuilder();
        for (String root : gitRoots) {
            if (root == null) continue;
            java.io.File usrBin = new java.io.File(root, "usr\\bin");
            java.io.File bin = new java.io.File(root, "bin");
            if (new java.io.File(usrBin, "bash.exe").isFile() || new java.io.File(usrBin, "bash").isFile()) {
                if (!extra.isEmpty()) extra.append(java.io.File.pathSeparator);
                extra.append(usrBin.getAbsolutePath());
                if (bin.isDirectory()) {
                    extra.append(java.io.File.pathSeparator).append(bin.getAbsolutePath());
                }
            }
        }
        if (extra.isEmpty()) {
            return path;
        }
        return extra + java.io.File.pathSeparator + path;
    }
}
