package com.gorundebug.servicearchitect;

import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.project.Project;
import com.intellij.util.EnvironmentUtil;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

final class GoToolchainEnvironment {
    static void configure(Project project, Path root, Map<String, String> env) throws IOException {
        // JetBrains loads the user's shell environment separately from System.getenv().
        // Use it as a fallback in IDEs without Go support, then prefer the project SDK.
        env.putAll(EnvironmentUtil.getEnvironmentMap());
        var plugin = PluginManagerCore.getPlugin(PluginId.getId("org.jetbrains.plugins.go"));
        if (plugin != null && plugin.isEnabled()) {
            applySdk(env, GoSdkEnvironment.sdkHome(project, root));
        }
    }

    static void applySdk(Map<String, String> env, Path home) throws IOException {
        if (home == null) return;
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows");
        Path executable = home.resolve("bin").resolve(windows ? "go.exe" : "go");
        if (!home.isAbsolute() || !Files.isRegularFile(executable) || !Files.isExecutable(executable)) {
            throw new IOException("The Go SDK selected in the IDE is unavailable: " + home
                + ". Select an installed local SDK in Settings > Go > GOROOT, then Refresh graph & calls.");
        }
        String pathKey = env.keySet().stream().filter(key -> key.equalsIgnoreCase("PATH")).findFirst().orElse("PATH");
        String previous = env.getOrDefault(pathKey, "");
        env.put(pathKey, executable.getParent() + (previous.isEmpty() ? "" : java.io.File.pathSeparator + previous));
        env.put("GOROOT", home.toString());
    }
}
