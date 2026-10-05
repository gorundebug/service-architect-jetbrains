package com.gorundebug.servicearchitect;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.jcef.JBCefApp;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.ui.jcef.JBCefBrowserBase;
import com.intellij.ui.jcef.JBCefJSQuery;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.FileVisitResult;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

final class GraphToolWindow implements Disposable {
    private final Project project;
    private final JPanel panel = new JPanel(new BorderLayout());
    private final JComboBox<ProjectChoice> projects = new JComboBox<>();
    private final JTextField outputDirectory = new JTextField("dist/generated-project", 20);
    private final JBCefBrowser browser;
    private final JBCefJSQuery readyQuery;
    private final JBCefJSQuery navigateQuery;
    private final AtomicLong generation = new AtomicLong();
    private volatile Path selectedRoot;
    private volatile String snapshotRevision = "";
    private volatile boolean disposed;

    GraphToolWindow(Project project) {
        this.project = project;
        JPanel toolbar = new JPanel(new BorderLayout());
        toolbar.add(new JLabel("Python project: "), BorderLayout.WEST);
        for (ProjectChoice choice : discoverProjects()) projects.addItem(choice);
        ProjectChoice initial = (ProjectChoice) projects.getSelectedItem();
        selectedRoot = initial == null ? null : initial.path;
        toolbar.add(projects, BorderLayout.CENTER);
        JButton refresh = new JButton("Refresh");
        refresh.addActionListener(event -> refresh());
        toolbar.add(refresh, BorderLayout.EAST);
        panel.add(toolbar, BorderLayout.NORTH);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEADING));
        actions.add(new JLabel("Generated project:"));
        actions.add(outputDirectory);
        JButton generate = new JButton("Generate + merge");
        generate.addActionListener(event -> runAction("ide-generate", outputDirectory.getText()));
        actions.add(generate);
        JButton materialize = new JButton("Materialize DSL");
        materialize.addActionListener(event -> runAction("ide-materialize", "python-dsl"));
        actions.add(materialize);
        panel.add(actions, BorderLayout.SOUTH);
        JPopupMenu contextMenu = new JPopupMenu();
        JMenuItem generateItem = new JMenuItem("Generate and merge project");
        generateItem.addActionListener(event -> runAction("ide-generate", outputDirectory.getText()));
        contextMenu.add(generateItem);
        JMenuItem materializeItem = new JMenuItem("Materialize effective Python DSL");
        materializeItem.addActionListener(event -> runAction("ide-materialize", "python-dsl"));
        contextMenu.add(materializeItem);
        toolbar.setComponentPopupMenu(contextMenu);
        projects.setComponentPopupMenu(contextMenu);
        if (!JBCefApp.isSupported()) {
            panel.add(new JLabel("JCEF is required for the graph viewer."), BorderLayout.CENTER);
            browser = null;
            readyQuery = null;
            navigateQuery = null;
            return;
        }
        browser = new JBCefBrowser();
        projects.addActionListener(event -> {
            ProjectChoice selected = (ProjectChoice) projects.getSelectedItem();
            selectedRoot = selected == null ? null : selected.path;
            refresh();
        });
        readyQuery = JBCefJSQuery.create((JBCefBrowserBase) browser);
        navigateQuery = JBCefJSQuery.create((JBCefBrowserBase) browser);
        readyQuery.addHandler(request -> {
            ApplicationManager.getApplication().invokeLater(this::refresh);
            return null;
        });
        navigateQuery.addHandler(request -> {
            navigate(request);
            return null;
        });
        String bridge = "window.addEventListener('load',function(){" + readyQuery.inject("'ready'") + "},{once:true});" +
            "window.addEventListener('service-architect:selection',function(event){" +
            navigateQuery.inject("JSON.stringify(event.detail)") + "});";
        String html = "<!doctype html><html><head><meta charset=\"utf-8\"><style>" +
            resource("/ide/designer.css") + "</style></head><body><div id=\"service-architect-designer\" data-snapshot-host=\"ide\"></div>" +
            "<script>" + bridge + "</script><script>" + resource("/ide/designer.js") +
            "</script></body></html>";
        browser.loadHTML(html);
        panel.add(browser.getComponent(), BorderLayout.CENTER);
    }

    JComponent component() { return panel; }

    private static String resource(String name) {
        try (InputStream stream = GraphToolWindow.class.getResourceAsStream(name)) {
            if (stream == null) throw new IllegalStateException("Missing plugin resource: " + name);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalStateException("Cannot read plugin resource: " + name, error);
        }
    }

    private Path root() {
        Path root = selectedRoot;
        if (root == null) throw new IllegalStateException("No .service-architect/project.yaml found in this IDE project");
        return root;
    }

    private String command(Path root) {
        String base = project.getBasePath();
        Path boundary = base == null ? root : Path.of(base).toAbsolutePath().normalize();
        for (Path current = root; current != null && current.startsWith(boundary); current = current.getParent()) {
            Path local = current.resolve(".venv/bin/sa-dsl");
            if (Files.isExecutable(local)) return local.toString();
        }
        return "sa-dsl";
    }

    private List<ProjectChoice> discoverProjects() {
        String base = project.getBasePath();
        if (base == null) return List.of();
        Path workspace = Path.of(base).toAbsolutePath().normalize();
        List<ProjectChoice> found = new ArrayList<>();
        Set<String> skip = Set.of(".git", ".venv", "node_modules", "build", "dist",
            ".dependencies", ".artifacts", "target");
        try {
            Files.walkFileTree(workspace, java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class), 8,
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {
                        if (!dir.equals(workspace) && skip.contains(dir.getFileName().toString())) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        if (Files.isRegularFile(dir.resolve(".service-architect/materialized.json"))) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        if (Files.isRegularFile(dir.resolve(".service-architect/project.yaml"))) {
                            found.add(new ProjectChoice(dir, workspace.relativize(dir).toString()));
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
        } catch (IOException ignored) { /* inaccessible siblings do not hide discovered projects */ }
        return found;
    }

    private record ProjectChoice(Path path, String label) {
        @Override public String toString() { return label.isEmpty() ? path.getFileName().toString() : label; }
    }

    private JsonObject invoke(Path root, List<String> args) throws Exception {
        ProcessBuilder builder = new ProcessBuilder();
        builder.command().add(command(root));
        builder.command().addAll(args);
        builder.directory(root.toFile());
        Process process = builder.start();
        CompletableFuture<String> stdout = CompletableFuture.supplyAsync(() -> read(process.getInputStream()));
        CompletableFuture<String> stderr = CompletableFuture.supplyAsync(() -> read(process.getErrorStream()));
        long timeout = args.get(0).equals("ide-generate") ? 10 * 60 * 1000 : Duration.ofSeconds(60).toMillis();
        if (!process.waitFor(timeout, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            throw new IOException("sa-dsl timed out");
        }
        String output = stdout.get(2, TimeUnit.SECONDS);
        try {
            return JsonParser.parseString(output).getAsJsonObject();
        } catch (RuntimeException error) {
            throw new IOException("sa-dsl returned invalid JSON: " + stderr.get(2, TimeUnit.SECONDS), error);
        }
    }

    private static String read(InputStream stream) {
        try { return new String(stream.readAllBytes(), StandardCharsets.UTF_8); }
        catch (IOException error) { return error.toString(); }
    }

    private void runAction(String action, String output) {
        if (output == null || output.isBlank()) {
            Messages.showWarningDialog(project, "Choose an output directory", "Service Architect");
            return;
        }
        Path selected = selectedRoot;
        if (selected == null) {
            Messages.showWarningDialog(project, "Select a Python project first", "Service Architect");
            return;
        }
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                JsonObject result = invoke(selected, List.of(action, "--project", selected.toString(),
                    "--output-dir", output));
                if (!result.get("status").getAsString().equals("success")) {
                    throw new IOException(result.has("message") ? result.get("message").getAsString() : action + " failed");
                }
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (!disposed) Messages.showInfoMessage(project,
                        "Completed in " + result.get("directory").getAsString(), "Service Architect");
                });
            } catch (Exception error) {
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (!disposed) Messages.showErrorDialog(project, error.getMessage(), "Service Architect");
                });
            }
        });
    }

    private void refresh() {
        long current = generation.incrementAndGet();
        snapshotRevision = "";
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            JsonObject result;
            try {
                Path root = root();
                result = invoke(root, List.of("ide-snapshot", "--project", root.toString()));
            } catch (Exception error) {
                result = new JsonObject();
                result.addProperty("status", "failed");
                result.addProperty("message", error.getMessage());
            }
            JsonObject response = result;
            JsonObject snapshot = response.has("snapshot") && response.get("snapshot").isJsonObject()
                ? response.getAsJsonObject("snapshot") : null;
            ApplicationManager.getApplication().invokeLater(() -> {
                if (!disposed && browser != null && current == generation.get()) {
                    if (snapshot != null) {
                        snapshotRevision = snapshot.get("revision").getAsString();
                        browser.getCefBrowser().executeJavaScript(
                            "window.dispatchEvent(new CustomEvent('service-architect:snapshot',{detail:" + snapshot + "}));",
                            browser.getCefBrowser().getURL(), 0);
                    } else {
                        Messages.showErrorDialog(project,
                            response.has("message") ? response.get("message").getAsString() : "Cannot export Python graph",
                            "Service Architect");
                    }
                }
            });
        });
    }

    private void navigate(String request) {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                JsonObject item = JsonParser.parseString(request).getAsJsonObject();
                if (!snapshotRevision.equals(item.get("revision").getAsString())) return;
                String kind = item.get("kind").getAsString();
                if (!kind.equals("node") && !kind.equals("link")) throw new IOException("Invalid selection");
                Path root = root();
                List<String> args = new java.util.ArrayList<>(List.of("ide-locate", "--project", root.toString(),
                    "--kind", kind, "--service", item.get("service").getAsString(),
                    "--key", item.get("key").getAsString()));
                if (kind.equals("link")) {
                    args.addAll(List.of("--source", item.get("source").getAsString(),
                        "--target", item.get("target").getAsString()));
                }
                JsonObject result = invoke(root, args);
                if (!result.get("status").getAsString().equals("success")) {
                    throw new IOException(result.has("message") ? result.get("message").getAsString() : "No source location");
                }
                JsonObject location = result.getAsJsonObject("location");
                Path file = root.resolve(location.get("file").getAsString()).toRealPath();
                if (!file.startsWith(root.toRealPath()) || !file.toString().endsWith(".py")) {
                    throw new IOException("Source path escapes the Python project");
                }
                VirtualFile virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file);
                if (virtualFile == null) throw new IOException("Python source not found: " + file);
                int line = Math.max(0, location.get("line").getAsInt() - 1);
                int column = Math.max(0, location.get("column").getAsInt() - 1);
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (!disposed) FileEditorManager.getInstance(project).openTextEditor(
                        new OpenFileDescriptor(project, virtualFile, line, column), true);
                });
            } catch (Exception error) {
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (!disposed) Messages.showWarningDialog(project, error.getMessage(), "Service Architect");
                });
            }
        });
    }

    @Override
    public void dispose() {
        disposed = true;
        if (readyQuery != null) readyQuery.dispose();
        if (navigateQuery != null) navigateQuery.dispose();
        if (browser != null) browser.dispose();
    }
}
