package com.gorundebug.servicearchitect;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.ui.Messages;

public final class ShowInGraphAction extends AnAction {
    @Override public ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }
    @Override public void update(AnActionEvent event) { event.getPresentation().setEnabled(event.getProject() != null); }
    @Override public void actionPerformed(AnActionEvent event) {
        var project = event.getProject();
        if (project == null) return;
        var view = project.getUserData(GraphToolWindow.INSTANCE);
        if (view == null) Messages.showInfoMessage(project, "Open the Service Architect tool window first.", "Service Architect");
        else view.showInGraph();
    }
}
