package com.gorundebug.servicearchitect;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.ide.impl.TrustedProjects;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.jcef.JBCefApp;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.ui.jcef.JBCefBrowserBase;
import com.intellij.ui.jcef.JBCefJSQuery;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JProgressBar;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
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
import java.util.function.Consumer;

final class GraphToolWindow implements Disposable {
    private final Project project;
    private final JPanel panel = new JPanel(new BorderLayout());
    private final JComboBox<ProjectChoice> projects = new JComboBox<>();
    private final JTextField outputDirectory = new JTextField("", 20);
    private final JButton generate = new JButton("Generate + merge");
    private final JMenuItem generateItem = new JMenuItem("Generate and merge project");
    private boolean actionRunning;
    private Runnable afterSetup;
    private static final String CLI_SETTING = "serviceArchitect.cliPath";
    private final JLabel cliStatus = new JLabel(" ");
    private final JButton setup = new JButton("Set up Service Architect");
    private final JButton cliSettings = new JButton("CLI settings...");
    private final JButton cancelSetup = new JButton("Cancel setup");
    private final JProgressBar setupProgress = new JProgressBar();
    private final Path managedCli = Path.of(PathManager.getSystemPath(), "service-architect", "cli", CliEnvironment.VERSION);
    private volatile boolean setupRunning;
    private volatile boolean setupCancelled;
    private final JBCefBrowser browser;
    private final JBCefJSQuery readyQuery;
    private final JBCefJSQuery navigateQuery;
    private final AtomicLong generation = new AtomicLong();
    private volatile Path selectedRoot;
    private boolean refreshingProjects;
    private volatile String snapshotRevision = "";
    private volatile boolean disposed;

    GraphToolWindow(Project project) {
        this.project = project;
        JPanel toolbar = new JPanel(new BorderLayout());
        toolbar.add(new JLabel("Python project path: "), BorderLayout.WEST);
        projects.setToolTipText("Directory containing the selected .service-architect/project.yaml");
        refreshProjects();
        toolbar.add(projects, BorderLayout.CENTER);
        JButton refresh = new JButton("Refresh");
        refresh.addActionListener(event -> {
            refreshProjects();
            refresh();
        });
        JPanel projectActions = new JPanel(new FlowLayout(FlowLayout.TRAILING));
        JButton create = new JButton("Create Project");
        create.setToolTipText("Create a Service Architect project in the IDE project root");
        create.addActionListener(event -> createProject());
        JButton importYaml = new JButton("Import from YAML");
        importYaml.setToolTipText("Create a Python SA project from an existing architecture YAML");
        importYaml.addActionListener(event -> {
            FileChooserDescriptor descriptor = new FileChooserDescriptor(true, false, false, false, false, false)
                .withTitle("Import Service Architect YAML")
                .withFileFilter(file -> "yaml".equalsIgnoreCase(file.getExtension()) || "yml".equalsIgnoreCase(file.getExtension()));
            FileChooser.chooseFile(descriptor, project, null, source -> {
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (!disposed && !project.isDisposed()) beginProjectCreation(Path.of(source.getPath()));
                });
            });
        });
        projectActions.add(create);
        projectActions.add(importYaml);
        projectActions.add(refresh);
        toolbar.add(projectActions, BorderLayout.EAST);
        JPanel header = new JPanel(new BorderLayout());
        header.add(toolbar, BorderLayout.NORTH);
        JPanel cliToolbar = new JPanel(new FlowLayout(FlowLayout.LEADING));
        setup.setVisible(false);
        setup.addActionListener(event -> setupCli());
        cliSettings.addActionListener(event -> configureCli());
        cancelSetup.setVisible(false);
        cancelSetup.addActionListener(event -> {
            setupCancelled = true;
            cliStatus.setText("Cancelling setup...");
            cancelSetup.setEnabled(false);
        });
        setupProgress.setIndeterminate(true);
        setupProgress.setVisible(false);
        cliToolbar.add(cliStatus);
        cliToolbar.add(setup);
        cliToolbar.add(cliSettings);
        cliToolbar.add(setupProgress);
        cliToolbar.add(cancelSetup);
        header.add(cliToolbar, BorderLayout.SOUTH);
        panel.add(header, BorderLayout.NORTH);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEADING));
        actions.add(new JLabel("Generated project path:"));
        outputDirectory.setToolTipText("Leave empty for the manifest destination (workspace root for new projects), or enter a path relative to the SA project");
        actions.add(outputDirectory);
        generate.addActionListener(event -> runAction("ide-generate", outputDirectory.getText()));
        actions.add(generate);
        JButton generatorSettings = new JButton("Generator access (.env)");
        generatorSettings.setToolTipText("Open the selected Python project's .env and configure its Service Architect API key");
        generatorSettings.addActionListener(event -> configureGeneration());
        actions.add(generatorSettings);
        JButton materialize = new JButton("Materialize DSL");
        materialize.addActionListener(event -> runAction("ide-materialize", "python-dsl"));
        actions.add(materialize);
        panel.add(actions, BorderLayout.SOUTH);
        JPopupMenu contextMenu = new JPopupMenu();
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
            if (refreshingProjects) return;
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

    private String command(Path root) throws IOException {
        String base = project.getBasePath();
        Path boundary = base == null ? root : Path.of(base).toAbsolutePath().normalize();
        return CliEnvironment.resolve(root, boundary,
            PropertiesComponent.getInstance(project).getValue(CLI_SETTING), managedCli).toString();
    }

    private void configureCli() {
        String value = Messages.showInputDialog(project,
            "Path to sa-dsl or a Python environment directory. Leave empty for automatic selection.",
            "Service Architect CLI", null,
            PropertiesComponent.getInstance(project).getValue(CLI_SETTING, ""), null);
        if (value == null) return;
        if (!value.isBlank()) {
            Path path;
            try { path = Path.of(value.trim()); }
            catch (RuntimeException error) {
                Messages.showWarningDialog(project, "Choose an absolute path to sa-dsl or a Python environment.", "Service Architect");
                return;
            }
            if (Files.isDirectory(path)) path = CliEnvironment.cliIn(path);
            if (!path.isAbsolute() || !Files.isRegularFile(path) || !Files.isExecutable(path)) {
                Messages.showWarningDialog(project, "The selected path does not contain an executable sa-dsl.", "Service Architect");
                return;
            }
            value = path.toString();
        }
        PropertiesComponent.getInstance(project).setValue(CLI_SETTING, value.trim(), "");
        refresh();
    }

    private void setupCli() {
        setupCli(false);
    }

    private void setupCli(boolean installationConfirmed) {
        if (disposed || setupRunning) return;
        if (!TrustedProjects.isTrusted(project)) {
            cliStatus.setText("Trust this project in the IDE before running its Python code.");
            return;
        }
        if (!installationConfirmed) {
            int answer = Messages.showYesNoDialog(project,
            "Install the bundled Service Architect CLI in a private plugin environment?\n" +
            "This downloads Python and dependencies, and uv from Astral if needed.\n" +
            "Your project environment and shell settings will not be changed.\n" +
            "After setup, Refresh executes the selected project's Python code.",
            "Set up Service Architect", Messages.getQuestionIcon());
            if (answer != Messages.YES) return;
        }
        setupRunning = true;
        setupCancelled = false;
        setup.setEnabled(false);
        cliSettings.setEnabled(false);
        setupProgress.setVisible(true);
        cancelSetup.setVisible(true);
        cancelSetup.setEnabled(true);
        cliStatus.setText("Preparing CLI setup...");
        panel.revalidate();
        panel.repaint();
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Service Architect: Installing CLI", true) {
          @Override public void run(ProgressIndicator indicator) {
            indicator.setIndeterminate(true);
            indicator.setText("Preparing isolated CLI environment...");
            String failure = null;
            try {
                CliEnvironment.install(managedCli, status -> {
                    indicator.setText(status);
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (!disposed && !setupCancelled) cliStatus.setText(status);
                    });
                }, () -> disposed || setupCancelled || indicator.isCanceled() || project.isDisposed());
            } catch (Exception error) { failure = error.getMessage(); }
            if (indicator.isCanceled()) setupCancelled = true;
            String message = failure;
            ApplicationManager.getApplication().invokeLater(() -> {
                setupRunning = false;
                if (disposed) return;
                setup.setEnabled(true);
                cliSettings.setEnabled(true);
                setupProgress.setVisible(false);
                cancelSetup.setVisible(false);
                if (message == null && !setupCancelled) {
                    setup.setVisible(false);
                    cliStatus.setText("CLI is ready. Loading graph...");
                    Runnable continuation = afterSetup;
                    afterSetup = null;
                    if (continuation != null) continuation.run();
                    else refresh();
                } else {
                    setup.setVisible(true);
                    cliStatus.setText(setupCancelled ? "Setup cancelled. You can retry." : "Setup failed. Check the error and retry.");
                    if (!setupCancelled) Messages.showErrorDialog(project,
                        message == null ? "CLI setup failed" : message, "Service Architect setup");
                }
            });
          }
        });
    }

    private void showMissingCli() {
        cliStatus.setText("Service Architect CLI is missing.");
        setup.setVisible(true);
        panel.revalidate();
    }

    private void createProject() {
        beginProjectCreation(null);
    }

    private void beginProjectCreation(Path sourceYaml) {
        if (project.getBasePath() == null) {
            Messages.showWarningDialog(project, "Open an IDE project folder first.", "Service Architect");
            return;
        }
        Path workspace = Path.of(project.getBasePath()).toAbsolutePath().normalize();
        String name = Messages.showInputDialog(project,
            "Create " + workspace.getFileName() + "-architecture inside " + workspace + ". Generated code will go into the workspace root.",
            sourceYaml == null ? "Create Service Architect Project" : "Import Service Architect Project from YAML", null,
            workspace.getFileName() == null ? "" : workspace.getFileName().toString(), null);
        if (name == null) return;
        if (name.isBlank() || name.contains("\n") || name.contains("\r") || name.indexOf('\0') >= 0) {
            Messages.showWarningDialog(project, "Enter a non-empty project name on one line.", "Service Architect");
            return;
        }
        createProject(workspace, name.trim(), sourceYaml);
    }

    private void createProject(Path workspace, String name, Path sourceYaml) {
        if (disposed || project.isDisposed()) return;
        if (setupRunning || actionRunning) {
            Messages.showWarningDialog(project,
                setupRunning ? "CLI setup is still running. Wait for it to finish, then retry."
                    : "A Service Architect action is already running. Wait for it to finish, then retry.",
                "Service Architect");
            return;
        }
        if (!TrustedProjects.isTrusted(project)) {
            Messages.showWarningDialog(project, "Trust this IDE project before creating a Service Architect project.", "Service Architect");
            return;
        }
        actionRunning = true;
        String title = sourceYaml == null ? "Service Architect: Create project" : "Service Architect: Import YAML";
        ProgressManager.getInstance().run(new Task.Backgroundable(project, title, false) {
          @Override public void run(ProgressIndicator indicator) {
            indicator.setIndeterminate(true);
            indicator.setText(sourceYaml == null ? "Creating Python project..." : "Importing YAML into a Python project...");
            try {
                List<String> args = new ArrayList<>(List.of("init", "--project", workspace.toString(), "--name", name));
                if (sourceYaml != null) args.addAll(List.of("--yaml", sourceYaml.toString()));
                JsonObject result = invoke(workspace, args);
                if (!result.get("status").getAsString().equals("success")) {
                    throw new IOException(result.has("message") ? result.get("message").getAsString() : "Cannot create project");
                }
                Path authoringRoot = Path.of(result.get("directory").getAsString());
                Path sourcePath = authoringRoot.resolve(result.get("source").getAsString()).normalize();
                if (!sourcePath.startsWith(authoringRoot) || !sourcePath.toString().endsWith(".py")) {
                    throw new IOException("Invalid imported Python source path");
                }
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (disposed) return;
                    selectedRoot = authoringRoot;
                    refreshProjects();
                    VirtualFile source = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(sourcePath);
                    if (source != null) FileEditorManager.getInstance(project).openTextEditor(new OpenFileDescriptor(project, source), true);
                    Messages.showInfoMessage(project, "Project created in " + authoringRoot, "Service Architect");
                    refresh();
                });
            } catch (CliEnvironment.MissingCliException error) {
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (disposed) return;
                    afterSetup = () -> createProject(workspace, name, sourceYaml);
                    showMissingCli();
                    int answer = Messages.showYesNoDialog(project,
                        "Service Architect CLI is not installed. Install it and continue?\n\n"
                            + "The bundled CLI will be installed in a private plugin environment.\n"
                            + "This downloads Python and dependencies, and uv from Astral if needed.\n"
                            + "Your project environment and shell settings will not be changed.\n\n"
                            + "After setup, this action will resume automatically and the resulting Python project will be loaded.",
                        "Service Architect", "Install and continue", "Cancel", Messages.getQuestionIcon());
                    if (answer == Messages.YES) setupCli(true);
                    else afterSetup = null;
                });
            } catch (Exception error) {
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (!disposed) Messages.showErrorDialog(project, error.getMessage(), "Service Architect");
                });
            } finally {
                ApplicationManager.getApplication().invokeLater(() -> actionRunning = false);
            }
          }
        });
    }

    private void refreshProjects() {
        Path previous = selectedRoot;
        DefaultComboBoxModel<ProjectChoice> model = new DefaultComboBoxModel<>();
        for (ProjectChoice choice : discoverProjects()) {
            model.addElement(choice);
            if (choice.path.equals(previous)) model.setSelectedItem(choice);
        }
        refreshingProjects = true;
        try {
            projects.setModel(model);
            ProjectChoice selected = (ProjectChoice) model.getSelectedItem();
            selectedRoot = selected == null ? null : selected.path;
        } finally {
            refreshingProjects = false;
        }
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
        @Override public String toString() { return path.toString(); }
    }

    private JsonObject invoke(Path root, List<String> args) throws Exception {
        return invoke(root, args, null);
    }

    private JsonObject invoke(Path root, List<String> args, Consumer<String> onProgress) throws Exception {
        if (!TrustedProjects.isTrusted(project)) {
            throw new IOException("Trust this project in the IDE before running its Python code, then click Refresh.");
        }
        ProcessBuilder builder = new ProcessBuilder();
        builder.command().add(command(root));
        builder.command().addAll(args);
        builder.directory(root.toFile());
        Process process = builder.start();
        CompletableFuture<String> stdout = CompletableFuture.supplyAsync(() -> read(process.getInputStream()));
        CompletableFuture<String> stderr = CompletableFuture.supplyAsync(() ->
            onProgress == null ? read(process.getErrorStream()) : readProgress(process.getErrorStream(), onProgress));
        long timeout = args.get(0).equals("ide-generate") ? Duration.ofMinutes(45).toMillis() : Duration.ofSeconds(60).toMillis();
        if (!process.waitFor(timeout, TimeUnit.MILLISECONDS)) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
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

    private static String readProgress(InputStream stream, Consumer<String> onProgress) {
        StringBuilder diagnostic = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    JsonObject event = JsonParser.parseString(line).getAsJsonObject();
                    if (event.has("type") && "service-architect:progress".equals(event.get("type").getAsString())
                            && event.has("message")) {
                        String message = event.get("message").getAsString();
                        onProgress.accept(message.substring(0, Math.min(message.length(), 500)));
                        continue;
                    }
                } catch (RuntimeException ignored) { /* Keep non-protocol stderr for diagnostics. */ }
                diagnostic.append(line).append('\n');
                if (diagnostic.length() > 8000) diagnostic.delete(0, diagnostic.length() - 8000);
            }
        } catch (IOException error) { diagnostic.append(error); }
        return diagnostic.toString();
    }

    private void configureGeneration() {
        try {
            Path directory = root();
            Path environment = directory.resolve(".env");
            Path ignore = directory.resolve(".gitignore");
            for (Path file : List.of(environment, ignore)) {
                if (Files.exists(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                        && !Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException(file + " must be a regular file, not a directory or symbolic link");
                }
            }
            String rules = Files.exists(ignore) ? Files.readString(ignore) : "";
            String trimmed = rules.stripTrailing();
            if (!trimmed.equals(".env") && !trimmed.endsWith("\n.env")) {
                Files.writeString(ignore, (rules.isEmpty() || rules.endsWith("\n") ? "" : "\n") + ".env\n",
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            }
            try {
                Files.writeString(environment,
                    "# Service Architect remote code generation credentials. Do not commit this file.\n"
                        + "# Set your Service Architect API key below, then run Generate and merge.\n"
                        + "# Importing YAML and viewing the graph do not require a key.\n"
                        + "# The API URL is configured by default; no AWS credentials are needed.\n"
                        + "SERVICE_ARCHITECT_API_KEY=\n",
                    java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE);
            } catch (java.nio.file.FileAlreadyExistsException ignored) { /* Preserve existing credentials. */ }
            VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(environment);
            if (file == null) throw new IOException("Cannot open " + environment);
            FileEditorManager.getInstance(project).openTextEditor(new OpenFileDescriptor(project, file), true);
        } catch (Exception error) {
            Messages.showErrorDialog(project, error.getMessage(), "Service Architect");
        }
    }

    private void runAction(String action, String output) {
        if (actionRunning) {
            Messages.showWarningDialog(project, "A Service Architect action is already running.", "Service Architect");
            return;
        }
        if (output == null || (!action.equals("ide-generate") && output.isBlank())) {
            Messages.showWarningDialog(project, "Choose an output directory", "Service Architect");
            return;
        }
        Path selected = selectedRoot;
        if (selected == null) {
            Messages.showWarningDialog(project, "Select a Python project first", "Service Architect");
            return;
        }
        actionRunning = true;
        String title = action.equals("ide-generate") ? "Service Architect: Generate and merge" : "Service Architect: Materialize DSL";
        generate.setEnabled(false);
        generateItem.setEnabled(false);
        ProgressManager.getInstance().run(new Task.Backgroundable(project, title, false) {
          @Override public void run(ProgressIndicator indicator) {
            indicator.setIndeterminate(true);
            indicator.setText("Preparing the project");
            try {
                List<String> args = new ArrayList<>(List.of(action, "--project", selected.toString()));
                if (!output.isBlank()) args.addAll(List.of("--output-dir", output.trim()));
                if (action.equals("ide-generate")) args.add("--progress-json");
                JsonObject result = invoke(selected, args, indicator::setText);
                if (!result.get("status").getAsString().equals("success")) {
                    throw new IOException(result.has("message") ? result.get("message").getAsString() : action + " failed");
                }
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (!disposed) Messages.showInfoMessage(project,
                        "Completed in " + result.get("directory").getAsString(), "Service Architect");
                });
            } catch (CliEnvironment.MissingCliException error) {
                ApplicationManager.getApplication().invokeLater(() -> { if (!disposed) showMissingCli(); });
            } catch (Exception error) {
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (!disposed) Messages.showErrorDialog(project, error.getMessage(), "Service Architect");
                });
            } finally {
                ApplicationManager.getApplication().invokeLater(() -> {
                    actionRunning = false;
                    if (!disposed) {
                        generate.setEnabled(true);
                        generateItem.setEnabled(true);
                    }
                });
            }
          }
        });
    }

    private void refresh() {
        if (disposed || setupRunning) return;
        if (selectedRoot == null) {
            generation.incrementAndGet();
            snapshotRevision = "";
            cliStatus.setText("No Service Architect project found. Click Create Project to get started.");
            return;
        }
        if (!TrustedProjects.isTrusted(project)) {
            generation.incrementAndGet();
            snapshotRevision = "";
            cliStatus.setText("Trust this project in the IDE, then click Refresh.");
            setup.setVisible(false);
            return;
        }
        long current = generation.incrementAndGet();
        snapshotRevision = "";
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            JsonObject result;
            try {
                Path root = root();
                result = invoke(root, List.of("ide-snapshot", "--project", root.toString()));
            } catch (CliEnvironment.MissingCliException error) {
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (!disposed && current == generation.get()) showMissingCli();
                });
                return;
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
                        cliStatus.setText(" ");
                        setup.setVisible(false);
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
