package com.gorundebug.servicearchitect;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Installs only after the user explicitly requests setup. Never modifies project environments. */
final class CliEnvironment {
    static final String VERSION = "0.1.9";
    private static final String UV_VERSION = "0.11.22";
    private static final boolean WINDOWS = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");

    static final class MissingCliException extends IOException {
        MissingCliException() {
            super("Service Architect CLI is not installed. Click Set up Service Architect, or choose your CLI in CLI settings.");
        }
    }

    static Path cliIn(Path environment) {
        return environment.resolve(WINDOWS ? "Scripts/sa-dsl.exe" : "bin/sa-dsl");
    }

    static Path resolve(Path projectRoot, Path workspace, String customPath, Path managedRoot) throws IOException {
        if (customPath != null && !customPath.isBlank()) {
            Path custom = Path.of(customPath).toAbsolutePath().normalize();
            if (Files.isDirectory(custom)) custom = cliIn(custom);
            if (!Files.isExecutable(custom) || !Files.isRegularFile(custom)) {
                throw new IOException("The selected CLI is unavailable: " + custom + ". Update CLI settings or clear the override.");
            }
            return custom;
        }
        Path boundary = workspace.toAbsolutePath().normalize();
        for (Path dir = projectRoot.toAbsolutePath().normalize(); dir != null && dir.startsWith(boundary); dir = dir.getParent()) {
            Path local = cliIn(dir.resolve(".venv"));
            if (Files.isExecutable(local) && Files.isRegularFile(local)) return local;
        }
        Path onPath = findExecutable("sa-dsl", false);
        if (onPath != null) return onPath;
        Path managed = cliIn(managedRoot.resolve("environment"));
        if (Files.isRegularFile(managedRoot.resolve("ready")) && Files.isExecutable(managed)) return managed;
        throw new MissingCliException();
    }

    private static Path findExecutable(String name, boolean includeCommonLocations) {
        List<Path> directories = new ArrayList<>();
        for (String value : System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator)) {
            // A relative PATH entry could execute a binary supplied by the current project.
            if (!value.isBlank() && Path.of(value).isAbsolute()) directories.add(Path.of(value));
        }
        if (includeCommonLocations) {
            Path home = Path.of(System.getProperty("user.home"));
            directories.add(home.resolve(".local/bin"));
            directories.add(home.resolve(".cargo/bin"));
            if (!WINDOWS) {
                directories.add(Path.of("/opt/homebrew/bin"));
                directories.add(Path.of("/usr/local/bin"));
            }
        }
        for (Path dir : directories) {
            Path executable = dir.resolve(name + (WINDOWS ? ".exe" : ""));
            if (Files.isRegularFile(executable) && Files.isExecutable(executable)) return executable;
        }
        return null;
    }

    static Path install(Path root, Consumer<String> progress, BooleanSupplier cancelled) throws Exception {
        Files.createDirectories(root);
        try (FileChannel channel = FileChannel.open(root.resolve("setup.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock lock;
            try { lock = channel.tryLock(); }
            catch (OverlappingFileLockException error) { lock = null; }
            if (lock == null) throw new IOException("Setup is running in another window. Wait for it to finish, then click Refresh.");
            try (FileLock acquired = lock) {
                checkCancelled(cancelled);
                Path environment = root.resolve("environment");
                Path cli = cliIn(environment);
                if (Files.isRegularFile(root.resolve("ready")) && Files.isExecutable(cli)) return cli;
                // Incomplete installations must never be selected by resolve().
                Files.deleteIfExists(root.resolve("ready"));
                Path uv = findExecutable("uv", true);
                if (uv == null) uv = bootstrapUv(root, progress, cancelled);
                Path bundle = root.resolve("sa-python-dsl.zip");
                try (InputStream input = CliEnvironment.class.getResourceAsStream("/cli/sa-python-dsl.zip")) {
                    if (input == null) throw new IOException("The plugin package is missing its bundled CLI. Reinstall the plugin.");
                    Files.copy(input, bundle, StandardCopyOption.REPLACE_EXISTING);
                }
                progress.accept("Preparing isolated Python 3.12 environment...");
                run(List.of(uv.toString(), "--no-config", "venv", "--python", "3.12", "--managed-python",
                    "--allow-existing", environment.toString()), root, Map.of(), cancelled);
                checkCancelled(cancelled);
                progress.accept("Installing bundled Service Architect CLI and dependencies...");
                Path python = environment.resolve(WINDOWS ? "Scripts/python.exe" : "bin/python");
                run(List.of(uv.toString(), "--no-config", "pip", "install", "--python", python.toString(),
                    bundle.toString()), root, Map.of(), cancelled);
                checkCancelled(cancelled);
                if (!Files.isRegularFile(cli) || !Files.isExecutable(cli)) throw new IOException("Setup did not create sa-dsl.");
                Files.writeString(root.resolve("ready"), VERSION, StandardCharsets.UTF_8);
                return cli;
            }
        }
    }

    private static Path bootstrapUv(Path root, Consumer<String> progress, BooleanSupplier cancelled) throws Exception {
        Path bin = root.resolve("tools");
        Path uv = bin.resolve(WINDOWS ? "uv.exe" : "uv");
        if (Files.isRegularFile(uv) && Files.isExecutable(uv)) return uv;
        progress.accept("Downloading uv " + UV_VERSION + " from Astral...");
        Path installer = root.resolve(WINDOWS ? "install-uv.ps1" : "install-uv.sh");
        URI uri = URI.create("https://astral.sh/uv/" + UV_VERSION + (WINDOWS ? "/install.ps1" : "/install.sh"));
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL).build()) {
            HttpResponse<InputStream> response = client.send(HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(45)).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() != 200) throw new IOException("Cannot download uv installer: HTTP " + response.statusCode());
                byte[] bytes = body.readNBytes(1024 * 1024 + 1);
                if (bytes.length > 1024 * 1024) throw new IOException("Unexpected uv installer size");
                Files.write(installer, bytes);
            }
        }
        checkCancelled(cancelled);
        progress.accept("Installing uv in the plugin's private directory...");
        List<String> command;
        if (WINDOWS) {
            Path powershell = Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"),
                "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
            command = List.of(powershell.toString(), "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", installer.toString());
        } else {
            command = List.of("/bin/sh", installer.toString());
        }
        run(command, root, Map.of("UV_UNMANAGED_INSTALL", bin.toString(), "UV_NO_MODIFY_PATH", "1"), cancelled);
        if (!Files.isRegularFile(uv) || !Files.isExecutable(uv)) throw new IOException("uv installation did not produce an executable.");
        return uv;
    }

    private static void run(List<String> command, Path root, Map<String, String> additionalEnvironment,
                            BooleanSupplier cancelled) throws Exception {
        checkCancelled(cancelled);
        ProcessBuilder builder = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true);
        builder.environment().remove("VIRTUAL_ENV");
        builder.environment().remove("CONDA_PREFIX");
        builder.environment().remove("PYTHONHOME");
        builder.environment().remove("PYTHONPATH");
        builder.environment().put("UV_CACHE_DIR", root.resolve("cache").toString());
        builder.environment().put("UV_PYTHON_INSTALL_DIR", root.resolve("python").toString());
        builder.environment().put("UV_PYTHON_DOWNLOADS", "automatic");
        builder.environment().put("UV_NO_PROGRESS", "1");
        builder.environment().putAll(additionalEnvironment);
        Process process = builder.start();
        process.getOutputStream().close();
        CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> {
            StringBuilder tail = new StringBuilder();
            try (var reader = process.inputReader(StandardCharsets.UTF_8)) {
                char[] buffer = new char[2048];
                int length;
                while ((length = reader.read(buffer)) != -1) {
                    tail.append(buffer, 0, length);
                    if (tail.length() > 8192) tail.delete(0, tail.length() - 8192);
                }
            } catch (IOException ignored) { /* process termination can close its output */ }
            return tail.toString();
        });
        long deadline = System.nanoTime() + Duration.ofMinutes(10).toNanos();
        try {
            while (!process.waitFor(250, TimeUnit.MILLISECONDS)) {
                checkCancelled(cancelled);
                if (System.nanoTime() > deadline) throw new IOException("Setup timed out. Check your network and retry.");
            }
            checkCancelled(cancelled);
            String log = output.get(5, TimeUnit.SECONDS);
            if (process.exitValue() != 0) throw new IOException("Setup command failed (exit " + process.exitValue() + "):\n" + log);
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancellationException("Setup cancelled");
    }
}
