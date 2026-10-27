package com.gorundebug.servicearchitect;

import com.intellij.openapi.ui.popup.JBPopup;
import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import java.lang.reflect.Proxy;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

class NavigationUiTest {
    @Test void chooserUsesExplicitInitiatingComponentInsteadOfGlobalFocus() {
        var owner = new JPanel();
        var calls = new ArrayList<String>();
        JBPopup popup = (JBPopup) Proxy.newProxyInstance(JBPopup.class.getClassLoader(), new Class<?>[]{JBPopup.class},
            (proxy, method, args) -> {
                calls.add(method.getName());
                assertEquals("showInCenterOf", method.getName());
                assertSame(owner, args[0]);
                return null;
            });
        NavigationUi.show(popup, owner);
        assertEquals(java.util.List.of("showInCenterOf"), calls);
    }

    @Test void detachedOrClosedOwnersCannotStealFocus() {
        assertFalse(NavigationUi.available(null));
        assertFalse(NavigationUi.available(new JPanel()));
    }
}
