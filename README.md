# Pi IntelliJ Diff Approval

JetBrains plugin + Pi extension that routes Pi Coding Agent file changes through IntelliJ review UI before they land, or batches them for review after a prompt finishes.

## What it does

- Starts a localhost-only IntelliJ approval server on demand.
- Installs/refreshes the bundled Pi extension into `~/.pi/agent/extensions/intellij-diff.ts`.
- Wraps Pi `edit` and `write` tools so creates/edits can be approved in IntelliJ.
- Intercepts simple shell deletions (`rm`, `rm -f`, `rm -r`, `unlink`) before execution.
- Blocks common shell-based file mutations while approval is enabled, so changes go through reviewed `edit`/`write` tools.
- Sends the active IntelliJ editor, current selection, and optional Project View focus to Pi as prompt context.
- Lets externally launched Pi sessions attach with a stable token across IDE restarts.

## Repository layout

- `intellij-plugin/` — JetBrains plugin written in Kotlin.
  - `PiDiffApprovalService.kt` — localhost HTTP server and IntelliJ diff/review orchestration.
  - `PiAgentLauncher.kt` — starts the server, installs the Pi extension, and launches Pi in the IDE terminal.
  - `PiToolWindowFactory.kt` — **Pi** tool window UI.
  - `PiDiffSettings*.kt` — persistent settings and Settings UI.
  - `AddToPiContextAction.kt` / `PiContextFocusService.kt` — Project View focused-context support.
- `pi-extension/intellij-diff.ts` — Pi extension that wraps file tools, adds slash commands, sends context/review requests, and guards deletions.

## Requirements

Plugin version: `0.2.3`.

The plugin targets IntelliJ Platform build `261` and newer, and bundles the JetBrains Terminal plugin dependency. The plugin project is pinned with asdf in `intellij-plugin/.tool-versions`:

```text
java temurin-17.0.19+10
gradle 9.5.0
```

Install/use pinned tools:

```bash
asdf plugin add java https://github.com/halcyon/asdf-java.git || true
asdf plugin add gradle https://github.com/rfrancis/asdf-gradle.git || true
cd intellij-plugin
asdf install
```

## Build and run

Run the plugin in a sandbox IDE:

```bash
cd intellij-plugin
JAVA_HOME="$(asdf where java)" ./gradlew runIde
```

Build the plugin zip:

```bash
cd intellij-plugin
JAVA_HOME="$(asdf where java)" ./gradlew buildPlugin
```

Validate the plugin:

```bash
cd intellij-plugin
JAVA_HOME="$(asdf where java)" ./gradlew build verifyPlugin
```

## Configure in IntelliJ

Open Settings → Tools/Other Settings → **Pi Diff Approval**.

Settings:

- port, default `63345`
- bearer token, generated locally by default
- Pi command, default `pi`
- review mode: `pre-apply` or `post-prompt-review`
- whether file creates, edits, and deletes require approval
- whether post-prompt-review originals stay in memory or are written to an originals cache directory

The token is stored in IntelliJ application settings (`piDiffApproval.xml`). The server binds to `127.0.0.1` only.

## Start Pi from IntelliJ

The plugin adds:

- a **Pi** tool window on the right with a small toolbar for **Copy External Start Command** and **Attach External Pi Token**, plus collapsible external-session settings
- a **Tools → Start Pi Agent** action
- a main toolbar **Start Pi Agent** action
- a Project View context-menu action: **Add to Pi Context**

Click **Start Pi Agent** from the main toolbar or Tools menu to:

1. start the local diff approval server
2. wait for `/api/pi/health`
3. install the bundled Pi extension
4. open an IDE terminal tab named **Pi Agent** at the project root
5. export `PI_INTELLIJ_DIFF_PORT` and `PI_INTELLIJ_DIFF_TOKEN`
6. run the configured Pi command

The diff server is not started at IDE startup. It starts on demand when launching or attaching Pi.

## Start Pi in an external terminal

Use **Copy External Start Command** to install the extension, start/wait for the server, and copy a command like:

```bash
cd '<project-root>' && PI_INTELLIJ_DIFF_PORT=63345 PI_INTELLIJ_DIFF_TOKEN='<token>' pi
```

Run that command in an external terminal. Keep IntelliJ open so approval dialogs can appear.

To keep one external Pi session alive across IDE restarts, start Pi yourself with a stable token:

```bash
cd '<project-root>' && PI_INTELLIJ_DIFF_PORT=63345 PI_INTELLIJ_DIFF_TOKEN='<stable-token>' pi
```

After IntelliJ restarts, open the **Pi** tool window, expand the external-session settings, paste the same token into **External Pi token**, and click the toolbar attach icon. The plugin stores that token, installs/refreshes the extension, starts the server, and lets the already-running Pi process attach to the new IntelliJ process.

## Manual Pi extension install

Normally the plugin installs the extension for you. For manual setup:

```bash
mkdir -p ~/.pi/agent/extensions
cp pi-extension/intellij-diff.ts ~/.pi/agent/extensions/
export PI_INTELLIJ_DIFF_PORT=63345
export PI_INTELLIJ_DIFF_TOKEN='<token from IDE settings>'
pi
```

Or pass flags:

```bash
pi --intellij-diff-port 63345 --intellij-diff-token '<token>'
```

To intentionally run Pi without IntelliJ diff approval for a session, the user can disable the integration explicitly:

```bash
PI_INTELLIJ_DIFF_DISABLED=1 pi
# or
pi --intellij-diff-disabled
```

When the integration is enabled and a token is configured, shell commands that look like direct file mutations are blocked because they bypass IntelliJ approval. This includes common redirection and commands such as `tee`, `cp`, `mv`, `touch`, `truncate`, `install`, and scripting one-liners that write, remove, rename, or copy files. Use Pi's `edit`/`write` tools, or disable the integration explicitly for that session.

Check connectivity from inside Pi:

```text
/intellij-diff-status
```

If this command is unknown, Pi did not load the extension. Start Pi from the plugin button again, or copy `pi-extension/intellij-diff.ts` into `~/.pi/agent/extensions/` manually and restart Pi.

## Review modes

### `pre-apply`

1. Pi calls `edit` or `write`.
2. Extension computes before/after content.
3. Extension posts to `http://127.0.0.1:<port>/api/pi/diff`.
4. IntelliJ opens a diff tab and shows **Accept** / **Reject** notification actions.
5. Extension writes only after accept.

No-op edits where `before === after` are allowed without a dialog.

### `post-prompt-review`

1. Extension records original file contents as baselines.
2. Pi runs tools normally during the prompt.
3. After the prompt, extension posts changed files to `/api/pi/review`.
4. IntelliJ opens a batch review UI that lists changed files with review state, file name, and a semantic change summary supplied by the Pi extension.
5. You can accept/reject all from the batch UI, request changes, add global feedback, or double-click a file to open its diff.
6. In the per-file diff popup, you can accept/reject the file or leave inline feedback comments.
7. Rejected files are restored to their original contents. Files marked as needing changes keep their original baseline so the next Pi revision is compared against the same starting point.

Baselines stay in memory by default. If disk storage is enabled, originals go to the configured originals cache directory.

In the per-file diff UI, highlight lines and click the gutter `+` icon, or use **Add Feedback from Selection**, to open an inline feedback editor inside the diff. Saved feedback appears embedded in the diff as multiline cards with **Edit** and **Delete** buttons in the bottom-right corner. The gutter marks the start and end of each commented range. Files with any feedback are automatically marked `feedback` / `requestChanges`, even if **Accept All** is used from the batch review UI.

## Deletion approval

Simple file deletions through `rm`, `rm -f`, `rm -r`, or `unlink` are intercepted before the shell command runs and show a deletion diff (`before` → empty file). Directory deletes are expanded recursively and capped at 50 files per command. Rejecting any deletion blocks the shell command.

Limit: detection is conservative. Deletions hidden inside arbitrary scripts cannot be reliably diff-gated before execution, so commands that look like shell-based file mutations are blocked while the integration is enabled.

## Automatic IntelliJ context

When Pi is launched with this plugin's environment, the extension asks IntelliJ for editor context before each user prompt is sent to the model.

Pi always receives the active editor tab path. If text is highlighted in the active editor, Pi also receives:

- file path
- line range
- selected text, truncated at 20,000 characters
- language/extension

This lets you ask about "this file" or highlight code and ask:

```text
why is this function taking 3 parameters?
```

You can also right-click files or directories in the Project View and choose **Add to Pi Context**. Selected files are sent as focused context for the next Pi prompt, then consumed. Directories are expanded recursively.

Debug context capture from inside Pi:

```text
/intellij-selection-status
```

If this says no selection, keep text selected in the IntelliJ editor and try again. If it lists focused files, Pi will read them only when needed. The context endpoint reads active editor and focused-file state from IntelliJ on demand.

## Troubleshooting

- Unknown `/intellij-diff-status`: the Pi extension did not load. Start Pi from IntelliJ again or reinstall `pi-extension/intellij-diff.ts` manually.
- Server unreachable: keep IntelliJ open, start or attach Pi from the **Pi** tool window, and verify `PI_INTELLIJ_DIFF_PORT` / `PI_INTELLIJ_DIFF_TOKEN` match the copied command.
- Shell command blocked: use Pi's `edit`/`write` tools for file changes, or intentionally disable the integration with `PI_INTELLIJ_DIFF_DISABLED=1` / `--intellij-diff-disabled`.
- No active editor context: focus an IntelliJ editor tab or choose **Add to Pi Context** in Project View, then run `/intellij-selection-status` inside Pi.

## Local HTTP API

The IntelliJ plugin exposes these localhost endpoints for the Pi extension:

- `GET /api/pi/health` — server status
- `GET /api/pi/settings` — review mode and approval settings
- `GET /api/pi/context` — active editor, selection, and focused files
- `POST /api/pi/diff` — single-file pre-apply diff approval
- `POST /api/pi/review` — post-prompt batch review

Requests require `Authorization: Bearer <token>` when a token is configured.

## CI and releases

GitHub Actions workflows live in `.github/workflows/`:

- `ci.yml` runs automatically on pushes and pull requests to `master` and `main`. It runs `./gradlew build` and `./gradlew verifyPlugin`.
- `release.yml` is manual-only through **Actions → Release → Run workflow**. It reads the plugin version from `intellij-plugin/build.gradle.kts`, creates an annotated tag (`v<version>` by default), builds the plugin zip, creates a GitHub release, and uploads `intellij-plugin/build/distributions/*.zip` as the release artifact.

Before running the release workflow, bump `version` in `intellij-plugin/build.gradle.kts` and merge the change to the release branch. The optional workflow `version` input must match the Gradle version when provided.

## Publishing to JetBrains Marketplace

Set JetBrains Marketplace credentials and run:

```bash
cd intellij-plugin
export CERTIFICATE_CHAIN='...'
export PRIVATE_KEY='...'
export PRIVATE_KEY_PASSWORD='...'
export PUBLISH_TOKEN='...'
JAVA_HOME="$(asdf where java)" ./gradlew publishPlugin
```

Plugin metadata lives in `intellij-plugin/build.gradle.kts` and `intellij-plugin/src/main/resources/META-INF/plugin.xml`.

## Security notes

- Server binds to `127.0.0.1` only.
- Requests require bearer-token auth when a token is configured.
- Token is generated locally and stored in IntelliJ application settings.
- The extension only talks to the configured local port.
- Deletion approval is best-effort for direct shell deletion commands, not arbitrary script behavior.
