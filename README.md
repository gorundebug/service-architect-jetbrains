# Service Architect for JetBrains IDEs

## Create a project

For an existing architecture, click **Import from YAML** beside **Create Project**,
select a `.yaml` or `.yml` file and confirm the project name (defaults to the IDE
root folder name). The existing YAML importer creates the same
`<workspace-folder>-architecture` layout, with Python declarations split into
files and generation targeting the workspace root. The selected name replaces
the model display name in the imported copy; the source YAML is not modified.
The plugin opens the imported entrypoint and graph. Import does not contact AWS
and refuses an existing destination. Original Python factories, loops and comments
cannot be recovered from YAML. The CLI equivalent is `sa-dsl init --yaml model.yaml`;
the YAML path can be absolute or relative to `--project`.

Open the workspace folder in the IDE and click **Create Project** in the Service
Architect tool window. The suggested name is the IDE root folder name; it can be
edited. The architecture folder is always named `<workspace-folder>-architecture`,
regardless of the display name. It contains `project.py`, `.service-architect/project.yaml`
and a `.gitignore` for local Python files, secrets and temporary artifacts.
The SA project root `.gitignore` excludes `.service-architect/build/`; no nested `.gitignore` is created. Creation
opens the Python file and selects the nested SA project in the viewer. Existing
architecture folders are never overwritten. If the CLI is missing,
**Set up Service Architect** prepares it and then resumes creation.

The shared CLI command is `sa-dsl init` (optionally `--project PATH --name NAME`).
Creation is local: it neither executes the new model nor calls AWS. It starts with
an empty model; add services and their languages in Python before code generation.
The manifest initially lists the supported generation targets, without creating
any service or choosing its implementation.

Read-only graph navigation for a typed `sa-python-dsl` project. Python remains the
source of truth. The plugin uses the same EmbeddedDesigner as Codex and VS Code,
including read-only Auto Layout. Refresh runs `sa-dsl ide-snapshot`, evaluates the
Python source and saves the resulting YAML to `canonical.output` from the manifest
(`.service-architect/build/<workspace-folder>.yaml` for new projects). The viewer receives the
same YAML, not a previously saved copy. The SA project root `.gitignore` is created
or extended with `.service-architect/build/` when saving into that directory. Invalid models do not
replace the last successfully exported YAML. `sa-dsl ide-locate` remains read-only.

Open a project containing `.service-architect/project.yaml` (possibly nested), then
open the **Service Architect** tool window. Select a Python project if the workspace
contains several and press **Refresh** after changing Python source.
Click a node or link to open its authoring expression in the editor.
Right-click a graph node (or use Shift+F10 / the Context Menu key on the selected
node) for **Python Code**, **Show Component** and **Show Pipeline**. Scope actions
appear only for actual membership, select the corresponding sidebar scope, and
never modify the Python model.
The tool window and its context menu also expose **Materialize DSL** (a reviewable
`python-dsl/model/` snapshot) and **Generate + merge**. Enter the generated project
destination in the field beside the buttons; this path is passed to the existing
ServiceGen merge contract. Leave the destination field empty to use
`generation.outputDirectory` from the selected SA project's manifest. New projects
set it to `..`, so generated services are placed in the workspace root beside
`<workspace-folder>-architecture`. A missing setting defaults to `.` for projects
already created at the workspace root. An explicit field value overrides the manifest.
When generating into the parent, the entire architecture directory is protected.
Generated files follow ServiceGen ownership rules; user-owned files are preserved
and stale files are not removed. Incoming paths that collide with loaded Python
authoring modules, credentials or project settings are rejected before merging.

Generation exports the model locally and calls the remote AWS API. Configure
`SERVICE_ARCHITECT_API_KEY` in the IDE process environment or the selected project's
`.env` file (exclude it from Git). `SERVICE_ARCHITECT_API_URL` optionally overrides
the API endpoint. Environment values take precedence over `.env`. The background
task displays export, submission, queue, server generation, download and merge
stages. Queue states are available with API-key authentication. Only the model is
uploaded, not business implementation files. Concurrent actions in the tool window
are blocked, and merge is not cancellable halfway through writing files.
Custom/project CLI installations must support `ide-generate --progress-json`.

If no CLI is available, click **Set up Service Architect** in the tool window.
After confirmation the plugin prepares a private Python 3.12 environment, installs
its bundled CLI and dependencies, and refreshes the graph. It finds existing `uv`
installations (including Homebrew and `~/.local/bin`) or installs pinned uv 0.11.22
from Astral into its own directory. Setup shows progress, supports cancellation
and can be retried. Internet access is required. Shell profiles and the project's
environment are not modified.

Setup uses the IDE's manual proxy, PAC and bypass rules for each destination.
The uv release archive is downloaded through the IDE HTTP client; Python and
dependency downloads use an authenticated, temporary loopback bridge. An HTTP
proxy's Basic challenge is handled explicitly through the IDE/JVM authenticator,
without changing global JVM authentication settings. Incorrect credentials fail
after a bounded retry. Other authentication schemes retain the JVM path and
depend on the IDE/runtime's support. Corporate credentials never enter the child
process environment or command line. HTTPS remains encrypted end-to-end through
the tunnel; certificate validation is not disabled. Corporate CA certificates
must be trusted by the system. The bridge closes when setup finishes or is cancelled.

CLI selection uses an explicit **CLI settings...** override first, then the nearest
project/ancestor `.venv`, then `sa-dsl` on `PATH`, then the private plugin environment.
The settings override accepts the absolute path to `sa-dsl` or an environment
directory. Use a project environment for projects with additional Python dependencies.
Clear the override to return to automatic selection.

The private environment is versioned under the IDE system directory:
`service-architect/cli/0.1.9`. An interrupted installation is not considered ready.
The plugin executes Python when refreshing, navigating or running CLI actions,
and blocks these actions while the IDE project is untrusted.
The embedded viewer requires JCEF (bundled with standard JetBrains IDE builds).

Run `make docker-build` to validate and package the plugin in Docker. The
installable ZIP is exported to `dist/`. The build copies project files into
the image; it does not mount source directories. CLI sources from sibling
`../sa-python-dsl` are bundled into the plugin. Override their location with
`SA_DSL_SOURCE=/path/to/sa-python-dsl` for Docker or `-PcliSource=...` for Gradle.
Set `DEPENDENCY_DOCKER_REGISTRY`
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
