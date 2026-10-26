package com.gorundebug.servicearchitect;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ImplementationNavigationTest {
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
