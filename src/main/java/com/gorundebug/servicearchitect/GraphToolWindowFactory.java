package com.gorundebug.servicearchitect;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

public final class GraphToolWindowFactory implements ToolWindowFactory {
    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        GraphToolWindow view = new GraphToolWindow(project);
        Content content = ContentFactory.getInstance().createContent(view.component(), "", false);
        content.setDisposer(view);
        toolWindow.getContentManager().addContent(content);
    }
}
