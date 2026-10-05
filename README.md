# Service Architect for JetBrains IDEs

Read-only graph navigation for a typed `sa-python-dsl` project. Python remains the
source of truth. The plugin uses the same EmbeddedDesigner as Codex and VS Code,
including read-only Auto Layout. It runs `sa-dsl ide-snapshot` and `sa-dsl ide-locate`
without writing canonical YAML.

Open a project containing `.service-architect/project.yaml` (possibly nested), then
open the **Service Architect** tool window. Select a Python project if the workspace
contains several and press **Refresh** after changing Python source.
Click a node or link to open its authoring expression in the editor.
The tool window and its context menu also expose **Materialize DSL** (a reviewable
`python-dsl/model/` snapshot) and **Generate + merge**. Enter the generated project
destination in the field beside the buttons; this path is passed to the existing
ServiceGen merge contract. Neither action rewrites the authoring Python project.

Install `sa-python-dsl` in the project virtual environment so that
`<project>/.venv/bin/sa-dsl` exists, or put `sa-dsl` on `PATH`. The plugin executes
the Python project when refreshing the graph, so only open trusted projects.
The embedded viewer requires JCEF (bundled with standard JetBrains IDE builds).

Run `make docker-build` to validate and package the plugin in Docker. The
installable ZIP is exported to `dist/`. The build copies project files into
the image; it does not mount source directories. Set `DEPENDENCY_DOCKER_REGISTRY`
to use a mirrored Gradle base image. The target platform is GoLand 2026.1.3;
`plugin.xml` permits compatible 2026.1 IDEs. For interactive local builds with
Gradle 9+, pass `-PlocalIde=/Applications/GoLand.app` on macOS to use an
installed IDE instead of downloading it.

The viewer assets originate in `service_architect_vue3/embedded` and are synchronized
as compiled bundles by `python3 sa-python-dsl/scripts/sync_ide_assets.py` from the
workspace root. Run it with `--check` before packaging to verify both IDE copies.
For an isolated Designer release checkout, pass its built asset directory with
`--source-dir` to both sync and check.
Do not edit the packaged bundles directly.
