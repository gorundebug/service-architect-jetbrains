package com.gorundebug.servicearchitect;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import com.google.gson.JsonObject;
import static org.junit.jupiter.api.Assertions.*;

class ImplementationNavigationTest {
    @TempDir Path workspace;

    @Test void ownerScopeRejectsArchiveCopiesForEveryLanguage() throws Exception {
        for (String language : List.of("GoLang", "CppUserver", "CppCoro", "Python", "Rust", "TypeScript")) {
            var target = new JsonObject();
            target.addProperty("owner", "orders"); target.addProperty("service", "orders"); target.addProperty("language", language);
            String ext = ImplementationNavigation.extensions(language).getFirst();
            Path live = null;
            for (String directory : List.of("orders", "other", ".artifacts/snapshot/orders", "docs/measurements/snapshot/orders")) {
                Path file = workspace.resolve(directory).resolve("a/b/c/Work." + ext);
                Files.createDirectories(file.getParent()); Files.writeString(file, "");
                assertEquals(directory.equals("orders"), ImplementationNavigation.implementationPath(workspace, file, target), language + ": " + directory);
                if (directory.equals("orders")) live = file;
            }
            assertEquals(List.of(workspace.resolve("orders").toRealPath()), ImplementationNavigation.ownerRoots(workspace, target));
            assertTrue(ImplementationNavigation.implementationPath(workspace.resolve("orders"), live, target));
            target.addProperty("owner", "missing");
            assertTrue(ImplementationNavigation.ownerRoots(workspace, target).isEmpty());
            target.addProperty("owner", "../orders");
            assertTrue(ImplementationNavigation.ownerRoots(workspace, target).isEmpty());
            target.remove("owner");
            assertTrue(ImplementationNavigation.ownerRoots(workspace, target).isEmpty());
        }
    }

    @Test void separateModulesAreDirectOwnersAndCannotEscapeThroughSymlinks() throws Exception {
        Files.createDirectory(workspace.resolve("shared_go"));
        Path outside = Files.createDirectory(workspace.resolve("outside"));
        // The generated output is a child, so the linked directory is outside it.
        Path output = Files.createDirectory(workspace.resolve("output"));
        Files.createDirectory(output.resolve("shared_go"));
        Files.createSymbolicLink(output.resolve("shared_external"), outside);
        var target = new JsonObject();
        target.addProperty("owner", "shared"); target.addProperty("service", "orders"); target.addProperty("language", "GoLang");
        assertEquals(List.of(output.resolve("shared_go").toRealPath()), ImplementationNavigation.ownerRoots(output, target));
    }
    @Test void languagesAndSourceSinkNamesAreSupportedWithoutGeneratedPaths() {
        for (String language : List.of("GoLang", "CppUserver", "CppCoro", "Rust", "Python", "TypeScript"))
            assertFalse(ImplementationNavigation.extensions(language).isEmpty());
        assertTrue(ImplementationNavigation.basenameMatches("work_source.go", List.of("WorkSource")));
        assertTrue(ImplementationNavigation.basenameMatches("work-source.ts", List.of("WorkSource")));
        assertTrue(ImplementationNavigation.basenameMatches("WorkSource.hpp", List.of("WorkSource")));
        assertFalse(ImplementationNavigation.basenameMatches("work_sink.go", List.of("WorkSource")));
        assertTrue(ImplementationNavigation.extensions("Unknown").isEmpty());
    }
}
