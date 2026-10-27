package com.gorundebug.servicearchitect;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.LineMarkerProvider;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.util.Key;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiNameIdentifierOwner;
import com.intellij.psi.PsiNamedElement;
import com.intellij.psi.util.PsiTreeUtil;

import javax.swing.Icon;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Exact, current declarations only. No CLI execution or project-wide symbol search. */
public final class GraphLineMarkerProvider implements LineMarkerProvider {
    record Context(Path authoring, JsonObject snapshot, String output) {}
    record Marker(int offset, List<EditorGraphNavigation.Node> nodes) {}
    private record Cache(Context context, long stamp, TreeMap<Integer, Marker> markers) {}
    private static final Key<Cache> CACHE = Key.create("serviceArchitect.lineMarkers");
    private static final class Icons {
        static final Icon GRAPH = IconLoader.getIcon("/icons/graphNode.svg", GraphLineMarkerProvider.class);
    }

    @Override public LineMarkerInfo<?> getLineMarkerInfo(PsiElement element) {
        // Daemon providers run for every PSI element: index once per file/revision,
        // and attach only to a leaf, as required by IntelliJ gutter providers.
        if (element.getFirstChild() != null) return null;
        PsiFile file = element.getContainingFile();
        if (file == null || file.getVirtualFile() == null) return null;
        GraphToolWindow view = element.getProject().getUserData(GraphToolWindow.INSTANCE);
        Context context = view == null ? null : view.markerContext();
        if (context == null) return null;
        Document document = PsiDocumentManager.getInstance(element.getProject()).getDocument(file);
        if (document == null || !PsiDocumentManager.getInstance(element.getProject()).isCommitted(document)) return null;
        long stamp = document.getModificationStamp();
        Cache cache = file.getUserData(CACHE);
        if (cache == null || !cache.context().equals(context) || cache.stamp() != stamp) {
            cache = new Cache(context, stamp, markers(context, file, document));
            file.putUserData(CACHE, cache);
            view.rememberMarkerFile(file);
        }
        Map.Entry<Integer, Marker> entry = cache.markers().ceilingEntry(element.getTextRange().getStartOffset());
        if (entry == null || entry.getKey() >= element.getTextRange().getEndOffset()) return null;
        Marker marker = entry.getValue();
        return new LineMarkerInfo<>(element, element.getTextRange(), Icons.GRAPH,
            ignored -> "Show in Graph — " + marker.nodes().size() + " node(s)",
            (event, ignored) -> view.showMarker(context, document, stamp, marker.nodes(), event.getComponent()),
            GutterIconRenderer.Alignment.LEFT, () -> "Service Architect: Show in Graph");
    }

    private static TreeMap<Integer, Marker> markers(Context context, PsiFile file, Document document) {
        var byLine = new TreeMap<Integer, Marker>();
        try {
            Path authoring = context.authoring().toRealPath();
            Path realFile = file.getVirtualFile().toNioPath().toRealPath();
            String relative = authoring.relativize(realFile).toString().replace('\\', '/');
            JsonObject snapshot = context.snapshot();
            JsonObject source = snapshot.has("sourceLocations") ? snapshot.getAsJsonObject("sourceLocations") : null;
            JsonObject files = source == null ? null : source.getAsJsonObject("files");
            if (files != null && files.has(relative)) {
                String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(document.getText().getBytes(StandardCharsets.UTF_8)));
                if (!digest.equals(files.get(relative).getAsString())) return new TreeMap<>();
                for (var service : source.getAsJsonObject("nodes").entrySet()) for (var node : service.getValue().getAsJsonObject().entrySet()) {
                    JsonObject location = node.getValue().getAsJsonObject();
                    var ranges = location.has("related") ? location.getAsJsonArray("related") : new com.google.gson.JsonArray();
                    if (!location.has("related")) ranges.add(location);
                    for (JsonElement value : ranges) {
                        JsonObject range = value.getAsJsonObject();
                        if (!relative.equals(range.get("file").getAsString())) continue;
                        int line = range.get("line").getAsInt() - 1;
                        int column = range.has("column") ? range.get("column").getAsInt() - 1 : 0;
                        if (line < 0 || line >= document.getLineCount() || column < 0) continue;
                        int offset = document.getLineStartOffset(line) + column;
                        if (offset >= document.getLineEndOffset(line)) continue;
                        add(byLine, line, offset, new EditorGraphNavigation.Node(service.getKey(), node.getKey()));
                    }
                }
            } else if (snapshot.has("implementations")) {
                String directory = context.output().isBlank() ? snapshot.get("generationOutputDirectory").getAsString() : context.output();
                Path generated = authoring.resolve(directory).toRealPath();
                for (PsiNamedElement declaration : PsiTreeUtil.findChildrenOfType(file, PsiNamedElement.class)) {
                    String name = declaration.getName();
                    if (name == null) continue;
                    PsiElement identifier = declaration instanceof PsiNameIdentifierOwner owner ? owner.getNameIdentifier() : declaration;
                    if (identifier == null) continue;
                    int offset = identifier.getTextOffset();
                    if (offset < 0 || offset >= document.getTextLength()) continue;
                    for (var service : snapshot.getAsJsonObject("implementations").entrySet()) for (var node : service.getValue().getAsJsonObject().entrySet()) {
                        for (JsonElement value : node.getValue().getAsJsonArray()) {
                            JsonObject target = value.getAsJsonObject();
                            if (!ImplementationNavigation.allowed(generated, file.getVirtualFile(), ImplementationNavigation.extensions(target.get("language").getAsString()))) continue;
                            for (JsonElement symbol : target.getAsJsonArray("symbols")) if (name.equals(symbol.getAsString())) {
                                add(byLine, document.getLineNumber(offset), offset, new EditorGraphNavigation.Node(service.getKey(), node.getKey())); break;
                            }
                        }
                    }
                }
            }
        } catch (com.intellij.openapi.progress.ProcessCanceledException canceled) { throw canceled; }
        catch (Exception ignored) { return new TreeMap<>(); }
        var result = new TreeMap<Integer, Marker>();
        for (Marker marker : byLine.values()) result.put(marker.offset(), marker);
        return result;
    }

    static void add(TreeMap<Integer, Marker> lines, int line, int offset, EditorGraphNavigation.Node node) {
        Marker previous = lines.get(line);
        var nodes = new LinkedHashSet<EditorGraphNavigation.Node>();
        if (previous != null) nodes.addAll(previous.nodes());
        nodes.add(node);
        lines.put(line, new Marker(previous == null ? offset : Math.min(previous.offset(), offset), new ArrayList<>(nodes)));
    }

    static void clearCache(PsiFile file) { file.putUserData(CACHE, null); }
}
