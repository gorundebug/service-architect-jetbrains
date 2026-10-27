package com.gorundebug.servicearchitect;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.CaretEvent;
import com.intellij.openapi.editor.event.CaretListener;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.fileEditor.FileEditorManagerEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiNamedElement;
import com.intellij.psi.util.PsiTreeUtil;

import javax.swing.Timer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** Editor events use the existing snapshot, never rerun Python or mutate source files. */
final class EditorGraphNavigation implements Disposable {
    record Node(String service, String key) {
        @Override public String toString() { return key + " — " + service; }
    }
    private record Symbols(Set<String> enclosing, Set<String> all) {}
    private record Declaration(String name, int start, int end) {}
    private record SymbolCache(com.intellij.openapi.editor.Document document, long stamp, List<Declaration> declarations) {}
    private volatile SymbolCache symbolCache;
    private final Project project;
    private final Supplier<Path> root;
    private final Supplier<JsonObject> snapshot;
    private final Supplier<String> output;
    private final Consumer<JsonObject> publish;
    private final AtomicLong revision = new AtomicLong();
    private final Timer timer;
    private volatile boolean disposed;
    private Editor lastEditor;

    EditorGraphNavigation(Project project, Supplier<Path> root, Supplier<JsonObject> snapshot,
                          Supplier<String> output, Consumer<JsonObject> publish) {
        this.project = project;
        this.root = root;
        this.snapshot = snapshot;
        this.output = output;
        this.publish = publish;
        timer = new Timer(180, event -> update(false));
        timer.setRepeats(false);
        EditorFactory.getInstance().getEventMulticaster().addCaretListener(new CaretListener() {
            @Override public void caretPositionChanged(CaretEvent event) {
                if (event.getEditor().getProject() == project) schedule(event.getEditor());
            }
        }, this);
        project.getMessageBus().connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER,
            new FileEditorManagerListener() {
                @Override public void selectionChanged(FileEditorManagerEvent event) { refresh(); }
            });
    }

    void refresh() { schedule(FileEditorManager.getInstance(project).getSelectedTextEditor()); }

    private void schedule(Editor editor) {
        if (disposed) return;
        if (editor != null) lastEditor = editor;
        revision.incrementAndGet();
        timer.restart();
    }

    void show() { timer.stop(); update(true); }

    void showNodes(JsonObject current, List<Node> nodes, BooleanSupplier fresh, java.awt.Component owner) {
        timer.stop();
        long request = revision.incrementAndGet();
        if (nodes.isEmpty() || !isCurrent(request, current) || !fresh.getAsBoolean() || !NavigationUi.available(owner)) return;
        if (nodes.size() == 1) send(current, nodes, nodes.getFirst());
        else NavigationUi.show(JBPopupFactory.getInstance().createPopupChooserBuilder(nodes).setTitle("Show in Graph — select a node")
            .setItemChosenCallback(node -> { if (isCurrent(request, current) && fresh.getAsBoolean() && NavigationUi.available(owner)) send(current, nodes, node); })
            .createPopup(), owner);
    }

    private void update(boolean focus) {
        if (disposed || project.isDisposed()) return;
        Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor == null) editor = lastEditor;
        JsonObject current = snapshot.get();
        Path authoring = root.get();
        if (editor == null || editor.isDisposed() || current == null || authoring == null) return;
        lastEditor = editor;
        Editor captured = editor;
        var owner = captured.getContentComponent();
        var document = editor.getDocument();
        VirtualFile file = FileDocumentManager.getInstance().getFile(document);
        if (file == null) return;
        long request = revision.incrementAndGet(), stamp = document.getModificationStamp();
        int offset = editor.getCaretModel().getOffset();
        int line = document.getLineNumber(offset) + 1;
        int column = offset - document.getLineStartOffset(line - 1) + 1;
        String text = document.getText(), directory = output.get();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                Symbols symbols = ReadAction.compute(() -> symbols(document, offset));
                Path realAuthoring = authoring.toRealPath();
                String configured = directory.isBlank() ? current.get("generationOutputDirectory").getAsString() : directory;
                String relative = realAuthoring.relativize(file.toNioPath().toRealPath()).toString().replace('\\', '/');
                JsonObject source = current.has("sourceLocations") ? current.getAsJsonObject("sourceLocations") : null;
                JsonObject files = source == null ? null : source.getAsJsonObject("files");
                boolean stale = files != null && files.has(relative) && !files.get(relative).getAsString().equals(
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))));
                List<Node> nodes = new ArrayList<>();
                if (!stale && files != null && files.has(relative)) nodes = sourceNodes(source, relative, line, column);
                else if (!stale) nodes = businessNodes(current, realAuthoring.resolve(configured).toRealPath(), file, symbols);
                List<Node> result = nodes;
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (!isCurrent(request, current) || captured.isDisposed() || document.getModificationStamp() != stamp) return;
                    if (!focus) { send(current, result, null); return; }
                    if (!NavigationUi.available(owner)) return;
                    if (result.isEmpty()) {
                        Messages.showInfoMessage(owner, stale ? "Refresh the graph after saving Python DSL changes." :
                            "No graph node is associated with this code. Refresh the graph and check the generated project directory.", "Service Architect");
                    } else if (result.size() == 1) send(current, result, result.getFirst());
                    else NavigationUi.show(JBPopupFactory.getInstance().createPopupChooserBuilder(result).setTitle("Show in Graph — select a node")
                        .setItemChosenCallback(node -> { if (isCurrent(request, current) && NavigationUi.available(owner)) send(current, result, node); })
                        .createPopup(), owner);
                });
            } catch (com.intellij.openapi.progress.ProcessCanceledException canceled) { throw canceled; }
            catch (Exception error) {
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (!isCurrent(request, current)) return;
                    if (focus && NavigationUi.available(owner)) Messages.showWarningDialog(owner, error.getMessage(), "Service Architect");
                    else send(current, List.of(), null);
                });
            }
        });
    }

    private boolean isCurrent(long request, JsonObject current) {
        return !disposed && !project.isDisposed() && revision.get() == request && snapshot.get() == current;
    }

    private void send(JsonObject current, List<Node> nodes, Node focus) {
        JsonObject event = new JsonObject();
        event.add("revision", current.get("revision"));
        JsonArray list = new JsonArray();
        for (Node node : nodes) list.add(json(node));
        event.add("nodes", list);
        if (focus != null) event.add("focus", json(focus));
        publish.accept(event);
    }

    private static JsonObject json(Node node) {
        JsonObject value = new JsonObject();
        value.addProperty("service", node.service()); value.addProperty("key", node.key());
        return value;
    }

    static boolean inRange(JsonObject range, int line, int column) {
        int startLine = range.get("line").getAsInt(), startColumn = range.has("column") ? range.get("column").getAsInt() : 1;
        int endLine = range.has("endLine") ? range.get("endLine").getAsInt() : startLine;
        int endColumn = range.has("endColumn") ? range.get("endColumn").getAsInt() : Integer.MAX_VALUE;
        return (line > startLine || line == startLine && column >= startColumn) &&
            (line < endLine || line == endLine && column < endColumn);
    }

    static List<Node> sourceNodes(JsonObject source, String file, int line, int column) {
        List<Node> result = new ArrayList<>();
        for (var service : source.getAsJsonObject("nodes").entrySet()) {
            for (var node : service.getValue().getAsJsonObject().entrySet()) {
                JsonObject location = node.getValue().getAsJsonObject();
                JsonArray positions = location.has("related") ? location.getAsJsonArray("related") : new JsonArray();
                if (!location.has("related")) positions.add(location);
                for (JsonElement position : positions) {
                    JsonObject item = position.getAsJsonObject();
                    if (file.equals(item.get("file").getAsString()) && inRange(item, line, column)) {
                        result.add(new Node(service.getKey(), node.getKey())); break;
                    }
                }
            }
        }
        return result;
    }

    private Symbols symbols(com.intellij.openapi.editor.Document document, int offset) {
        Set<String> enclosing = new LinkedHashSet<>(), all = new LinkedHashSet<>();
        SymbolCache cache = symbolCache;
        if (cache == null || cache.document() != document || cache.stamp() != document.getModificationStamp()) {
            List<Declaration> declarations = new ArrayList<>();
            var psi = PsiDocumentManager.getInstance(project).getPsiFile(document);
            if (psi != null) {
                for (PsiNamedElement element : PsiTreeUtil.findChildrenOfType(psi, PsiNamedElement.class)) {
                    var range = element.getTextRange();
                    declarations.add(new Declaration(element.getName(), range.getStartOffset(), range.getEndOffset()));
                }
            }
            cache = new SymbolCache(document, document.getModificationStamp(), declarations);
            symbolCache = cache;
        }
        for (Declaration item : cache.declarations()) {
            tokens(all, item.name());
            if (offset >= item.start() && offset < item.end()) tokens(enclosing, item.name());
        }
        return new Symbols(enclosing, all);
    }

    private static void tokens(Set<String> result, String name) {
        if (name == null) return;
        var matcher = Pattern.compile("[\\p{L}_][\\p{L}\\p{N}_]*").matcher(name);
        while (matcher.find()) result.add(matcher.group());
    }

    private static List<Node> businessNodes(JsonObject snapshot, Path root, VirtualFile file, Symbols symbols) {
        List<Set<Node>> groups = List.of(new LinkedHashSet<>(), new LinkedHashSet<>(), new LinkedHashSet<>());
        if (!snapshot.has("implementations")) return List.of();
        for (var service : snapshot.getAsJsonObject("implementations").entrySet()) {
            for (var node : service.getValue().getAsJsonObject().entrySet()) {
                for (JsonElement value : node.getValue().getAsJsonArray()) {
                    JsonObject target = value.getAsJsonObject();
                    if (!ImplementationNavigation.allowed(root, file, target)) continue;
                    List<String> names = new ArrayList<>();
                    for (JsonElement name : target.getAsJsonArray("symbols")) names.add(name.getAsString());
                    Node identity = new Node(service.getKey(), node.getKey());
                    if (names.stream().anyMatch(symbols.enclosing()::contains)) groups.get(0).add(identity);
                    else if (names.stream().anyMatch(symbols.all()::contains)) groups.get(1).add(identity);
                    else if (ImplementationNavigation.basenameMatches(file.getName(), names)) groups.get(2).add(identity);
                }
            }
        }
        return groups.stream().filter(group -> !group.isEmpty()).findFirst().map(ArrayList::new).orElseGet(ArrayList::new);
    }

    @Override public void dispose() { disposed = true; revision.incrementAndGet(); timer.stop(); symbolCache = null; lastEditor = null; }
}
