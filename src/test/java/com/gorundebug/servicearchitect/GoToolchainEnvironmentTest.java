package com.gorundebug.servicearchitect;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class GoToolchainEnvironmentTest {
    @TempDir Path directory;

    @Test void selectedSdkOverridesMissingPathAndStaleGoroot() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(System.getProperty("os.name").startsWith("Windows"));
        Path home = directory.resolve("Go SDK");
        Path executable = home.resolve("bin/go");
        Files.createDirectories(executable.getParent());
        Files.writeString(executable, "#!/bin/sh\nprintf selected-sdk");
        assertTrue(executable.toFile().setExecutable(true));
        var env = new HashMap<>(Map.of("PATH", "/usr/bin:/bin", "GOROOT", "/old-sdk"));
        GoToolchainEnvironment.applySdk(env, home);
        assertEquals(home.toString(), env.get("GOROOT"));
        var builder = new ProcessBuilder("/bin/sh", "-c", "go version");
        builder.environment().putAll(env);
        var process = builder.start();
        assertEquals("selected-sdk", new String(process.getInputStream().readAllBytes()));
        assertEquals(0, process.waitFor());
    }

    @Test void missingSdkPreservesFallbackAndBrokenSelectionFailsClearly() {
        var env = new HashMap<>(Map.of("PATH", "/usr/bin"));
        assertDoesNotThrow(() -> GoToolchainEnvironment.applySdk(env, null));
        assertEquals(Map.of("PATH", "/usr/bin"), env);
        var error = assertThrows(java.io.IOException.class, () -> GoToolchainEnvironment.applySdk(env, directory));
        assertTrue(error.getMessage().contains("Settings > Go > GOROOT"));
        assertEquals(Map.of("PATH", "/usr/bin"), env);
    }
}
