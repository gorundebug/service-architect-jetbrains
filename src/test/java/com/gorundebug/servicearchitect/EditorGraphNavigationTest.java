package com.gorundebug.servicearchitect;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EditorGraphNavigationTest {
    @Test void rangesAreMultilineAndEndExclusive() {
        var range = JsonParser.parseString("{\"line\":2,\"column\":3,\"endLine\":4,\"endColumn\":9}").getAsJsonObject();
        assertFalse(EditorGraphNavigation.inRange(range, 2, 2));
        assertTrue(EditorGraphNavigation.inRange(range, 3, 1));
        assertTrue(EditorGraphNavigation.inRange(range, 4, 8));
        assertFalse(EditorGraphNavigation.inRange(range, 4, 9));
    }
    @Test void sharedFactoryHighlightsAllInstancesAndIndividualCallerOnlyItsNode() {
        var source = JsonParser.parseString("""
            {"nodes":{"svc":{
              "one":{"related":[{"file":"model.py","line":4},{"file":"model.py","line":8}]},
              "two":{"related":[{"file":"model.py","line":4},{"file":"model.py","line":9}]}
            }}}
            """).getAsJsonObject();
        assertEquals(2, EditorGraphNavigation.sourceNodes(source, "model.py", 4, 1).size());
        assertEquals("one", EditorGraphNavigation.sourceNodes(source, "model.py", 8, 1).getFirst().key());
        assertTrue(EditorGraphNavigation.sourceNodes(source, "other.py", 4, 1).isEmpty());
    }
}
