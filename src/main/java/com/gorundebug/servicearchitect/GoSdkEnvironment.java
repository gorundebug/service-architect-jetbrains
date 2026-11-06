package com.gorundebug.servicearchitect;

import com.goide.sdk.GoSdkService;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import java.nio.file.Path;

/** Loaded only when the optional JetBrains Go plugin is enabled. */
final class GoSdkEnvironment {
    static Path sdkHome(Project project, Path root) {
        return ReadAction.compute(() -> {
            var file = LocalFileSystem.getInstance().findFileByNioFile(root);
            var module = file == null ? null : ModuleUtilCore.findModuleForFile(file, project);
            var sdk = GoSdkService.getInstance(project).getSdk(module);
            var home = sdk.getHomePath();
            return home == null || home.isBlank() ? null : Path.of(home);
        });
    }
}
