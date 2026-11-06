package com.gorundebug.servicearchitect;

import com.google.gson.JsonObject;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Both IDEs use the same model-driven analysis; no relationships are authored. */
final class SubstreamCalls implements Disposable {
    interface Invoker { JsonObject run(Path root, List<String> args) throws Exception; }
    private final AtomicLong epoch = new AtomicLong();
    private final Supplier<JsonObject> snapshot;
    private final Consumer<JsonObject> publish;
    private volatile boolean disposed;

    SubstreamCalls(Supplier<JsonObject> snapshot, Consumer<JsonObject> publish) {
        this.snapshot = snapshot;
        this.publish = publish;
    }

    void invalidate() {
        long current = epoch.incrementAndGet();
        JsonObject value = snapshot.get();
        if (value == null) return;
        send(current, message(value, "cleared", null));
    }

    private JsonObject message(JsonObject snapshot, String status, String error) {
        JsonObject result = new JsonObject();
        result.add("revision", snapshot.get("revision"));
        result.addProperty("status", status);
        if (error != null) result.addProperty("message", error);
        return result;
    }

    private void send(long current, JsonObject result) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (!disposed && epoch.get() == current) publish.accept(result);
        });
    }

    // Invoked on EDT, capturing UI settings before starting background work.
    void run(Path root, String output, JsonObject selection, Invoker invoke) {
        JsonObject expected = snapshot.get();
        if (expected == null || !expected.get("revision").equals(selection.get("revision"))) return;
        long current = epoch.incrementAndGet();
        if (FileDocumentManager.getInstance().getUnsavedDocuments().length > 0) {
            send(current, message(expected, "failed", "Save source changes before analyzing SubStream calls"));
            return;
        }
        send(current, message(expected, "running", null));
        String directory = output.isBlank() ? expected.get("generationOutputDirectory").getAsString() : output;
        var args = new java.util.ArrayList<>(List.of("ide-substream-calls", "--project", root.toString(),
            "--revision", selection.get("revision").getAsString(), "--output-dir", directory));
        if (selection.has("service")) args.addAll(List.of("--service", selection.get("service").getAsString()));
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            JsonObject result;
            try { result = invoke.run(root, args); }
            catch (Exception error) { result = message(expected, "failed", error.getMessage()); }
            if (snapshot.get() == expected) send(current, result);
        });
    }

    @Override public void dispose() { disposed = true; epoch.incrementAndGet(); }
}
