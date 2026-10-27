package com.gorundebug.servicearchitect;

import javax.swing.SwingUtilities;
import com.intellij.openapi.ui.popup.JBPopup;
import java.awt.Component;
import java.awt.KeyboardFocusManager;
import java.awt.Window;

/** Navigation belongs to its initiating window, never the globally focused IDE frame. */
final class NavigationUi {
    private NavigationUi() {}

    static boolean available(Component owner) {
        if (owner == null || !owner.isShowing()) return false;
        Window window = SwingUtilities.getWindowAncestor(owner);
        if (window == null) return false;
        // A chooser can itself own keyboard focus. Accept its owner chain, but
        // never another project's frame in the same IDE process.
        for (Window active = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
             active != null; active = active.getOwner()) {
            if (active == window) return true;
        }
        return false;
    }

    static void show(JBPopup popup, Component owner) {
        popup.showInCenterOf(owner);
    }
}
