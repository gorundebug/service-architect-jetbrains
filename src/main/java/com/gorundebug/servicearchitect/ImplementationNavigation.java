package com.gorundebug.servicearchitect;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.navigation.ChooseByNameContributor;
import com.intellij.navigation.NavigationItem;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.BooleanSupplier;

/** IDE-native, language-plugin-owned symbol lookup. Never executes generated code. */
final class ImplementationNavigation {
    private record Candidate(VirtualFile file, int offset, String label) {
        @Override public String toString() { return label; }
    }

    static List<String> extensions(String language) {
        return switch (language) {
            case "GoLang" -> List.of("go");
            case "CppUserver", "CppCoro" -> List.of("cpp", "cc", "cxx", "hpp", "h", "hxx");
            case "Rust" -> List.of("rs");
            case "Python" -> List.of("py");
            case "TypeScript" -> List.of("ts", "tsx");
            default -> List.of();
        };
    }

    static boolean basenameMatches(String filename, List<String> symbols) {
        int dot = filename.lastIndexOf('.');
        String stem = dot < 0 ? filename : filename.substring(0, dot);
        String normalized = stem.replace("_", "").replace("-", "");
        return symbols.stream().anyMatch(symbol -> normalized.equalsIgnoreCase(symbol.replace("_", "").replace("-", "")));
    }

    static boolean allowed(Path root, VirtualFile file, List<String> extensions) {
        if (file == null || !extensions.contains(file.getExtension())) return false;
        try {
            Path real = file.toNioPath().toRealPath();
            if (!real.startsWith(root)) return false;
            for (Path part : root.relativize(real)) {
                if (List.of(".git", "node_modules", ".venv", "venv", "target", "build", "dist", "vendor", ".dependencies").contains(part.toString())) return false;
            }
            return true;
        } catch (Exception ignored) { return false; }
    }

    private static List<Candidate> find(Project project, Path root, JsonArray targets) {
        var candidates = new LinkedHashMap<String, Candidate>();
        var contributors = new ArrayList<>(ChooseByNameContributor.SYMBOL_EP_NAME.getExtensionList());
        contributors.addAll(ChooseByNameContributor.CLASS_EP_NAME.getExtensionList());
        for (JsonElement value : targets) {
            JsonObject target = value.getAsJsonObject();
            List<String> symbols = new ArrayList<>();
            for (JsonElement symbol : target.getAsJsonArray("symbols")) symbols.add(symbol.getAsString());
            List<String> extensions = extensions(target.get("language").getAsString());
            var resolved = new LinkedHashMap<String, Candidate>();
            for (String name : symbols) {
                for (ChooseByNameContributor contributor : contributors) {
                    for (NavigationItem item : contributor.getItemsByName(name, name, project, false)) {
                        if (!(item instanceof PsiElement element) || !symbols.contains(item.getName())) continue;
                        PsiElement declaration = element.getNavigationElement();
                        if (declaration.getContainingFile() == null) continue;
                        VirtualFile file = declaration.getContainingFile().getVirtualFile();
                        if (!allowed(root, file, extensions)) continue;
                        int offset = declaration.getTextOffset();
                        String label = item.getName() + " — " + root.relativize(file.toNioPath()) + " [" + target.get("role").getAsString() + "]";
                        resolved.put(file.getPath() + ":" + offset, new Candidate(file, offset, label));
                    }
                }
            }
            if (resolved.isEmpty()) {
                for (String extension : extensions) {
                    for (VirtualFile file : FilenameIndex.getAllFilesByExt(project, extension, GlobalSearchScope.projectScope(project))) {
                        if (!allowed(root, file, extensions) || !basenameMatches(file.getName(), symbols)) continue;
                        resolved.put(file.getPath(), new Candidate(file, 0, root.relativize(file.toNioPath()) + " — file match only (symbol unavailable)"));
                    }
                }
            }
            candidates.putAll(resolved);
        }
        return new ArrayList<>(candidates.values());
    }

    // Called on EDT: capture UI settings before doing index work on a pooled thread.
    static void open(Project project, Path authoringRoot, JsonObject snapshot, JsonObject selection,
                     String override, BooleanSupplier current) {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                JsonObject services = snapshot.getAsJsonObject("implementations");
                JsonObject nodes = services == null ? null : services.getAsJsonObject(selection.get("service").getAsString());
                JsonArray targets = nodes == null ? null : nodes.getAsJsonArray(selection.get("key").getAsString());
                if (targets == null || targets.isEmpty()) throw new IllegalStateException("This node has no user-owned implementation");
                String directory = override.isBlank() ? snapshot.get("generationOutputDirectory").getAsString() : override;
                Path root = authoringRoot.resolve(directory).toRealPath();
                List<Candidate> candidates = DumbService.getInstance(project).runReadActionInSmartMode(() -> find(project, root, targets));
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (project.isDisposed() || !current.getAsBoolean()) return;
                    if (candidates.isEmpty()) {
                        Messages.showInfoMessage(project, "Implementation not found under " + root + ". Check the generated project directory and language support, and wait for indexing.", "Service Architect");
                        return;
                    }
                    java.util.function.Consumer<Candidate> navigate = item -> {
                        if (!project.isDisposed() && current.getAsBoolean() && item.file().isValid())
                            FileEditorManager.getInstance(project).openTextEditor(new OpenFileDescriptor(project, item.file(), item.offset()), true);
                    };
                    if (candidates.size() == 1) navigate.accept(candidates.getFirst());
                    else JBPopupFactory.getInstance().createPopupChooserBuilder(candidates)
                        .setTitle("Select business implementation").setItemChosenCallback(item -> navigate.accept(item)).createPopup().showInFocusCenter();
                });
            } catch (com.intellij.openapi.progress.ProcessCanceledException canceled) {
                throw canceled;
            } catch (Exception error) {
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (!project.isDisposed() && current.getAsBoolean()) Messages.showWarningDialog(project, error.getMessage(), "Service Architect");
                });
            }
        });
    }
}
