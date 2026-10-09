package com.gorundebug.servicearchitect;

import com.intellij.util.io.HttpRequests;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
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
import java.util.zip.ZipInputStream;

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
        String arch = switch (System.getProperty("os.arch").toLowerCase(Locale.ROOT)) {
            case "aarch64", "arm64" -> "aarch64";
            case "amd64", "x86_64" -> "x86_64";
            default -> throw new IOException("Automatic CLI setup does not support this processor: " + System.getProperty("os.arch"));
        };
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String platform;
        if (WINDOWS) platform = "pc-windows-msvc";
        else if (os.contains("mac")) platform = "apple-darwin";
        else if (os.contains("linux")) platform = "unknown-linux-musl";
        else throw new IOException("Automatic CLI setup does not support this operating system: " + os);
        String archiveName = "uv-" + arch + "-" + platform;
        Files.createDirectories(bin);
        Path staging = Files.createTempDirectory(bin, ".uv-install-");
        Path archive = staging.resolve(WINDOWS ? "download.zip" : "download.tar.gz");
        Path executable = staging.resolve(WINDOWS ? "uv.exe" : "uv");
        try {
            progress.accept("Downloading uv " + UV_VERSION + " using IDE network settings...");
            String url = "https://github.com/astral-sh/uv/releases/download/" + UV_VERSION + "/"
                + archiveName + (WINDOWS ? ".zip" : ".tar.gz");
            HttpRequests.request(url).useProxy(true).connectTimeout(20_000).readTimeout(45_000).connect(request -> {
                try (InputStream body = request.getInputStream(); var output = Files.newOutputStream(archive)) {
                    copyBounded(body, output, 64L * 1024 * 1024, cancelled);
                }
                return null;
            });
            checkCancelled(cancelled);
            progress.accept("Installing uv in the plugin's private directory...");
            if (WINDOWS) {
                boolean found = false;
                try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
                    for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                        checkCancelled(cancelled);
                        if (!entry.isDirectory() && (entry.getName().equals("uv.exe") || entry.getName().equals(archiveName + "/uv.exe"))) {
                            if (found) throw new IOException("Duplicate uv executable in release archive");
                            try (var output = Files.newOutputStream(executable)) {
                                copyBounded(zip, output, 128L * 1024 * 1024, cancelled);
                            }
                            found = true;
                        }
                    }
                }
            } else {
                run(List.of("/usr/bin/tar", "-xzf", archive.toString(), "-C", staging.toString(),
                    "--strip-components=1", archiveName + "/uv"), root, Map.of(), cancelled);
                if (!executable.toFile().setExecutable(true, true)) throw new IOException("Cannot make uv executable");
            }
            if (!Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS) || !Files.isExecutable(executable)) {
                throw new IOException("uv release archive did not contain an executable.");
            }
            checkCancelled(cancelled);
            Files.move(executable, uv, StandardCopyOption.REPLACE_EXISTING);
            return uv;
        } finally {
            Files.deleteIfExists(executable);
            Files.deleteIfExists(archive);
            Files.deleteIfExists(staging);
        }
    }

    private static void copyBounded(InputStream input, java.io.OutputStream output, long limit,
                                    BooleanSupplier cancelled) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            checkCancelled(cancelled);
            total += count;
            if (total > limit) throw new IOException("Unexpected uv release size");
            output.write(buffer, 0, count);
        }
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
        try (CliProxyEnvironment proxy = CliProxyEnvironment.configure(builder.environment())) {
            runProcess(builder, cancelled, proxy);
        }
    }

    private static void runProcess(ProcessBuilder builder, BooleanSupplier cancelled,
                                   CliProxyEnvironment proxy) throws Exception {
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
            if (process.exitValue() != 0) {
                // Corporate credentials stay in the bridge; redact its process-local token.
                String detail = proxy.redact(log);
                if (!proxy.diagnostic().isBlank()) detail += "\n" + proxy.diagnostic();
                throw new IOException("Setup command failed (exit " + process.exitValue() + "):\n" + detail);
            }
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
