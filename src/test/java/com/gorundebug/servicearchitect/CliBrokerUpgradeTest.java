package com.gorundebug.servicearchitect;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CliBrokerUpgradeTest {
    @TempDir Path root;
    private Path ready(String version) throws Exception {
        Path directory = root.resolve("cli").resolve(version);
        Path executable = CliEnvironment.cliIn(directory.resolve("environment"));
        Files.createDirectories(executable.getParent());
        Files.writeString(executable, "#!/bin/sh\n");
        assertTrue(executable.toFile().setExecutable(true));
        Files.writeString(directory.resolve("ready"), version);
        return executable;
    }
    @Test void staleManagedRuntimeDoesNotMaskBundledBrokerUpgrade() throws Exception {
        ready("0.1.22");
        ready("0.1.23");
        assertNotEquals("0.1.23", CliEnvironment.VERSION);
        Path current = root.resolve("cli").resolve(CliEnvironment.VERSION);
        assertThrows(CliEnvironment.MissingCliException.class,
            () -> CliEnvironment.resolve(root, root, "", current));
        Path expected = ready(CliEnvironment.VERSION);
        assertEquals(expected, CliEnvironment.resolve(root, root, "", current));
    }
}
