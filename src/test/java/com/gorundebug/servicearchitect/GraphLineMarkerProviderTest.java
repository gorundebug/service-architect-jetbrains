package com.gorundebug.servicearchitect;

import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GraphLineMarkerProviderTest {
    @Test void oneGutterIconPerLineRetainsEveryNodeWithoutDuplicates() {
        var lines = new TreeMap<Integer, GraphLineMarkerProvider.Marker>();
        var one = new EditorGraphNavigation.Node("svc", "one");
        var two = new EditorGraphNavigation.Node("svc", "two");
        GraphLineMarkerProvider.add(lines, 2, 20, one);
        GraphLineMarkerProvider.add(lines, 2, 12, two);
        GraphLineMarkerProvider.add(lines, 2, 20, one);
        GraphLineMarkerProvider.add(lines, 4, 40, one);
        assertEquals(2, lines.size());
        assertEquals(12, lines.get(2).offset());
        assertEquals(java.util.List.of(one, two), lines.get(2).nodes());
        assertEquals(java.util.List.of(one), lines.get(4).nodes());
    }
}
