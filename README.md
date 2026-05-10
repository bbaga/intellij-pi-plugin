# Pi IntelliJ Diff Approval

Publishable JetBrains plugin + Pi extension that routes Pi Coding Agent file mutations through an IntelliJ diff approval dialog.

## Layout

- `intellij-plugin/` — JetBrains plugin. Starts a loopback HTTP server when a project opens.
- `pi-extension/intellij-diff.ts` — Pi extension overriding built-in `edit` and `write` tools.

## Build/run IntelliJ plugin

The plugin project is pinned with asdf in `intellij-plugin/.tool-versions`:

```text
java temurin-17.0.19+10
gradle 9.5.0
```

Install/use the pinned tools:

```bash
asdf plugin add java https://github.com/halcyon/asdf-java.git || true
asdf plugin add gradle https://github.com/rfrancis/asdf-gradle.git || true
cd intellij-plugin
asdf install
JAVA_HOME="$(asdf where java)" ./gradlew runIde
```

Build the plugin zip:

```bash
JAVA_HOME="$(asdf where java)" ./gradlew buildPlugin
```

In the IDE, open Settings → Tools/Other Settings → **Pi Diff Approval** and configure:

- port, default `63345`
- bearer token
- Pi command, default `pi`

## Start Pi from IntelliJ

The plugin adds:

- a **Pi** tool window on the right with **Start Pi Agent** and **Copy External Start Command** buttons
- a **Tools → Start Pi Agent** action
- a main toolbar **Start Pi Agent** action

Starting Pi from the **Pi** tool window, menu, or toolbar starts the local diff approval server, waits until `/api/pi/health` responds, creates a terminal tab named **Pi Agent** in the IDE terminal area, changes to the project root, installs the bundled Pi extension to `~/.pi/agent/extensions/intellij-diff.ts`, exports `PI_INTELLIJ_DIFF_PORT` / `PI_INTELLIJ_DIFF_TOKEN`, and runs the configured Pi command. The diff server is not started at IDE startup; it starts on demand when launching Pi.

Use **Copy External Start Command** to install the bundled Pi extension, start/wait for the diff server, and copy a command like:

```bash
cd '<project-root>' && PI_INTELLIJ_DIFF_PORT=63345 PI_INTELLIJ_DIFF_TOKEN='<token>' pi
```

Run that command in an external terminal window when you do not want to use IntelliJ's terminal. Keep IntelliJ open so the diff approval server can show dialogs.

## Install Pi extension

```bash
mkdir -p ~/.pi/agent/extensions
cp pi-extension/intellij-diff.ts ~/.pi/agent/extensions/
export PI_INTELLIJ_DIFF_PORT=63345
export PI_INTELLIJ_DIFF_TOKEN='<token from IDE settings>'
pi
```

Check connectivity from inside pi:

```text
/intellij-diff-status
```

If this command is unknown, Pi did not load the extension. Start Pi from the plugin button again, or copy `pi-extension/intellij-diff.ts` into `~/.pi/agent/extensions/` manually and restart Pi.

Or pass flags:

```bash
pi --intellij-diff-port 63345 --intellij-diff-token '<token>'
```

## Automatic editor selection context

When Pi is launched with this plugin's environment, the Pi extension asks IntelliJ for the current editor selection before each user prompt is sent to the model. The selection is prepended to the prompt text so it is visible to the model as ordinary context.

If text is highlighted in the active editor, Pi automatically receives:

- file path
- line range
- selected text
- language/extension

So you can highlight a function and ask in Pi:

```text
why is this function taking 3 parameters?
```

without manually copying the code into the prompt.

Debug selection capture from inside Pi:

```text
/intellij-selection-status
```

If this says no selection, keep the code selected in the IntelliJ editor and try again. The context endpoint reads the active editor selection from IntelliJ on demand.

## Flow

1. Pi calls `edit` or `write`.
2. Extension computes before/after.
3. Extension posts to `http://127.0.0.1:<port>/api/pi/diff`.
4. IntelliJ shows a modal diff with **Accept** / **Reject**.
5. Extension writes only when accepted.
6. Simple file deletions through `rm`, `rm -f`, `rm -r`, or `unlink` are intercepted before the shell command runs and show a deletion diff (`before` → empty file). Rejected deletion blocks the shell command.

No-op edits where `before === after` are allowed without showing a dialog.

Deletion guard limitations: deletion detection is intentionally conservative. It covers normal shell `rm` / `unlink` commands issued by Pi. Deletions hidden inside arbitrary scripts such as `python -c 'os.remove(...)'` cannot be reliably diff-gated before execution.

## Publishing

Set JetBrains Marketplace credentials and run:

```bash
cd intellij-plugin
export CERTIFICATE_CHAIN='...'
export PRIVATE_KEY='...'
export PRIVATE_KEY_PASSWORD='...'
export PUBLISH_TOKEN='...'
JAVA_HOME="$(asdf where java)" ./gradlew publishPlugin
```

## Security

- Server binds to `127.0.0.1` only.
- Requests require `Authorization: Bearer <token>` when token is configured.
- Token is generated locally and stored in IntelliJ application settings.
