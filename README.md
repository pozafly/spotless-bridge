# Spotless Bridge

English | [한국어](README.ko.md)

**Format Java with your project's own Spotless rules after every AI edit and every IntelliJ save, without waiting for Gradle each time.**

Spotless Bridge keeps your project's Spotless formatter warm in a small background daemon.
AI hooks and the IntelliJ plugin send files to that daemon instead of running `spotlessApply`,
so repeated formatting skips Gradle configuration and formatter startup.

Formatting rules and versions come from your existing `spotless { java { ... } }` configuration.
There is no separate editor style to maintain and no global service to install.

## Why Spotless Bridge?

I wanted Spotless to run whenever I saved a file in IntelliJ or an AI tool generated code. But calling
`spotlessApply` each time meant waiting for Gradle configuration and task execution, even for a single
file and even with a warm Gradle daemon.

Frontend tooling solves this with long-running processes: TypeScript's
[tsserver](https://github.com/microsoft/TypeScript/wiki/Standalone-Server-%28tsserver%29) stays up to
answer editor requests, and the [Prettier VS Code extension](https://github.com/prettier/prettier-vscode)
loads the project's own Prettier. Spotless Bridge applies the same idea to Spotless: it **spends a little
memory to keep the formatter ready instead of waiting for it on every edit**.

## Native hook client (0.2.0 development)

Version 0.2.0 is not published yet; use the [source installation guide](docs/install-from-source.md) to try it.
The Gradle plugin now includes a Rust CLI. Applying the plugin during a Gradle run installs the matching
executable under the project's `.gradle/spotless-bridge/`; existing hook commands stay the same.
Users do **not** need Rust, Node.js, Python, a global CLI installation, or a separate download command.
JDK 17+ is still required for Gradle and the Java daemon.

On supported platforms, warm hooks use `launcher → native CLI → existing Java daemon`, with no new
client JVM or Gradle invocation. The daemon validates settings, selects targets, formats and writes the files.
Missing/stopped daemons, changed settings and new paths trigger one Gradle preparation outside the daemon.
Communication failures after sending a request are reported without an automatic retry.

The release build collects clients for macOS, Linux and Windows on x64 and ARM64. Linux uses static musl
builds. Other platforms retain the Java client. The six native CI jobs build and test each packaged client;
these jobs must pass before claiming cross-platform verification. Local source builds include only the host client.

## Formatting latency

**The 0.2.0 development build took about 14 ms through the Rust CLI, or 22 ms through the existing AI hook launcher.**
The complete hook was about **22× faster than `spotlessApply`** and **5.2× faster than the Java hook client** in this sample.

| Route | Median | What is timed |
| --- | ---: | --- |
| **Rust CLI — direct request** | **14 ms** | Native process startup, settings checks, daemon request, formatting and file write |
| **AI hook — project launcher → Rust CLI** | **22 ms** | Shell launcher and the complete native request above |
| **IntelliJ plugin — persistent formatter client¹** | **10 ms** | In-process client call, settings checks, daemon request and formatted text returned |
| Java hook client — fallback | 114 ms | New client JVM, settings checks, daemon request, formatting and file write |
| `./gradlew spotlessApply` | 483 ms | Complete Gradle command and file write |
| `./gradlew spotlessBridgeApply` | 495 ms | Complete Gradle command and file write |

¹ The IntelliJ row measures the same persistent `GradleFormatter.format` client used by the plugin,
plus its line-ending normalization, in a standalone Java harness. IDE event dispatch, document application,
disk save and UI rendering are excluded. It is not a timed IDE save action.
The native routes include process startup and disk writes, so the measurement boundaries differ.
The hook timings also exclude the AI product's own event dispatch.

Use the project launcher for AI hooks. Both `./gradlew` commands still evaluate the build on each invocation;
the native launcher reuses the daemon without Gradle or a new client JVM for known targets while settings are unchanged.
Missing daemons, changed settings and new paths still require Gradle preparation.

Measured on October 16, 2026 with a **local, unpublished 0.2.0 development build**, macOS ARM64,
Corretto JDK 21.0.6, Gradle 8.14.3, Spotless 8.10.2, and Google Java Format 1.30.0.
Each route received the same unformatted 1,545-byte Java file: five warm-ups and twenty measured runs,
with the execution order rotated. Both daemons were warm, configuration cache was disabled,
and every formatting result matched `spotlessApply` byte for byte. Warm native requests made **zero Gradle calls**
and retained the same daemon PID. Direct native calls ranged from 12–17 ms, and launcher calls from 20–26 ms.
A single first hook request, with Gradle warm but no Bridge daemon prepared, took **1.56 s**.
Dependency downloads were excluded. These are local sample results, not latency guarantees for other projects or platforms.
[Raw measurements](docs/benchmarks/native-2026-10-16.json).

Earlier Java-client batching measurements used a separate 0.1.1 development build and smaller, 88-byte files:
20 files improved from 260 ms to 171 ms after batching preparation. These are historical Java-client results,
not Rust batch measurements. Task-completion hooks should still pass all changed files in one invocation.
[Earlier measurements and conditions](docs/benchmarks/latency-2026-10-16.json).

## Quick start

You need a Gradle project with Spotless Java formatting configured and its Gradle wrapper.
Bridge requires **Gradle 8.14.3, Spotless 8.0.0, and JDK 17** or newer; see
[Requirements and compatibility](#requirements-and-compatibility) for the accepted ranges.

### 1. Apply the plugin

Add Spotless Bridge to the `plugins` block of your **root** `build.gradle`, next to Spotless:

```groovy
plugins {
    id 'com.diffplug.spotless' version '8.10.2'   // your existing Spotless
    id 'io.github.pozafly.spotlessbridge' version '0.1.1'
}
```

In Kotlin DSL, add `id("io.github.pozafly.spotlessbridge") version "0.1.1"`.
Keep your existing Spotless version and rules. In a multi-project build, apply Bridge once at the root
and leave each module's Spotless configuration as it is.

### 2. Format the project

```sh
./gradlew spotlessBridgeApply
```

This formats every Spotless Java target, with the same result as `spotlessApply`.
The daemon starts automatically on the first run; there is no separate start step.

### 3. Connect your AI tool

The plugin keeps a small launcher in `.gradle/spotless-bridge/` and updates it on every Gradle run, such as a build
or an IntelliJ Gradle sync. There is nothing to install or commit. Have your AI tool run it with the Java files it changed:

```sh
JAVA_HOME=/path/to/jdk .gradle/spotless-bridge/spotless-bridge \
  src/main/java/example/Customer.java \
  src/test/java/example/CustomerTest.java
```

For task-completion hooks, pass all changed Java files in one invocation, as above.
One Java client prepares the whole batch; configuration and daemon checks are shared across the files,
and new or excluded files trigger one preparation refresh for the batch instead of one per file.
The launcher does not queue separate hook events.

`JAVA_HOME` must point to JDK 17 or newer. It is only used when the launcher runs Gradle to prepare the engine.
On Windows, use `.gradle\spotless-bridge\spotless-bridge.cmd`.

If you prefer immediate formatting after each edit, in Claude Code you can add a `PostToolUse` hook
to `.claude/settings.json`.
It formats each Java file right after Claude edits it. This example uses `jq` to read the edited path
and assumes the Claude Code project directory is the Gradle root:

```json
{
  "hooks": {
    "PostToolUse": [
      {
        "matcher": "Edit|Write",
        "hooks": [
          {
            "type": "command",
            "command": "jq -r '.tool_input.file_path // empty' | grep '\\.java$' | tr '\\n' '\\0' | JAVA_HOME=/path/to/jdk xargs -0 \"$CLAUDE_PROJECT_DIR/.gradle/spotless-bridge/spotless-bridge\""
          }
        ]
      }
    ]
  }
}
```

Other AI tools work the same way: collect the Java files the task changed and pass them to the launcher.
That's it. The first request starts the daemon, and later requests reuse it.

## How it works

```text
Your Gradle project's Spotless configuration
                    |
                    v
          Project formatting daemon
             /                \
            /                  \
   IntelliJ plugin          AI hook
   Reformat / Save          Changed files
```

1. The first request runs the project's Gradle wrapper to read the Spotless configuration and start the daemon.
2. While the configuration is unchanged, later requests for known target files go straight to the daemon without Gradle.
3. If the configuration changes, a file isn't a known target, or the daemon isn't running, Gradle starts or refreshes it.

A few terms used in this document:

- **Engine**: the Spotless Bridge plugin JAR, copied into `.gradle/spotless-bridge/` so the daemon and hooks can run it
  outside Gradle. Its version is the plugin version you apply.
- **Daemon**: one background process per Gradle root. It is separate from both IntelliJ and the Gradle daemon,
  so it keeps serving hooks after you close the IDE.
- **Worker**: the process the daemon runs to load your project's Spotless formatter and keep it in memory.

Because each Gradle root has its own daemon, every project keeps its own formatter versions and rules.
The daemon shuts down after 30 minutes without requests and starts again on the next one.

## Gradle tasks

The tasks are listed under **SpotlessBridge tasks** in `./gradlew tasks`.

| Command | What it does |
| --- | --- |
| `./gradlew spotlessBridgeApply` | Formats every Spotless Java target, with the same result as `spotlessApply` |
| `./gradlew spotlessBridgeApply -PspotlessBridgeFiles=src/main/java/A.java,src/main/java/B.java` | Formats only the listed files; files that are not Spotless targets are skipped |
| `./gradlew spotlessBridgeApply -PspotlessBridgeFiles=changed-files.txt` | Formats the files listed one per line in a UTF-8 file |
| `./gradlew spotlessBridgeStart` | Starts the daemon or reloads the Spotless configuration; other tasks do this when needed |
| `./gradlew spotlessBridgeStatus` | Prints the daemon PID, or `-1` when no daemon is running |
| `./gradlew spotlessBridgeStop` | Stops the daemon and its worker |

Paths are absolute or relative to the Gradle root. These tasks evaluate Gradle on every run,
so AI hooks should use the launcher instead.

## AI hook details

- Each argument is a file to format, absolute or relative to the Gradle root. The launcher finds the Gradle root
  from its own location, so it can run from any directory.
- The launcher appears after Gradle has run once in the project. In a fresh clone, run any Gradle command
  or open the project in IntelliJ first.
- Pass only existing files; leave out deleted ones. With no file arguments, the launcher does nothing.
- Errors are reported in one line, such as `Spotless Bridge: file not found: <path>`, with a nonzero exit code.
  Treat that as a hook failure.
- The first call prepares the engine through Gradle. Later calls start the engine's Java client directly,
  so known target files are formatted without Gradle while the configuration is unchanged.
- IntelliJ doesn't need to be installed or running.
- If you used 0.1.0, which ran `tools/spotless-bridge/ai-hook.py`, point your hook at the launcher
  and delete `tools/spotless-bridge/`.

After formatting, keep running your project's usual checks: `spotlessCheck`, compilation, and relevant tests.
If you use OpenRewrite, run it before Bridge so the rewritten code gets formatted.

## IntelliJ IDEA (preview)

The IntelliJ plugin applies your Spotless rules when you run **Reformat Code** or save a Java file.
It uses the same daemon as your AI hook. It is not on JetBrains Marketplace yet; until it is,
[build and install it from source](docs/install-from-source.md#2-build-and-install-the-intellij-plugin).
AI hooks and Gradle tasks work without it.

IDE compatibility targets **2025.3–2026.2**. One ZIP is built against 2025.3 and checked with Plugin Verifier
against six pinned builds (the first release and latest pinned patch of each series). See the
[exact versions and verification limits](docs/install-from-source.md#verify-the-same-zip-across-ide-versions-without-publishing).
Safe Mode blocks formatting until you trust the project in IntelliJ.

After installing it:

1. Open **Settings → Tools → Spotless Bridge** and enable the plugin.
   Set a JDK 17 or newer that also meets your formatter's requirements; use the same JDK as the hook.
2. If needed, set the Gradle root. Use the same root as your AI hook.
3. Run **Reformat Code** on a Java source file to format it with your Spotless rules.
4. To format on save, enable **Tools → Actions on Save → Reformat code** with **Java / Whole file**.
   Disable overlapping Java formatters and the Optimize imports, Rearrange code, and Code cleanup
   save actions so they don't modify the result afterward.

The plugin formats writable local Java files that IntelliJ recognizes as source. Selection-only
formatting isn't supported. IntelliJ receives text with LF line endings; files written by hooks
follow the project's Spotless encoding and line-ending settings.

## Requirements and compatibility

| Tool | Minimum version | Accepted range |
| --- | --- | --- |
| Gradle | 8.14.3 | 8.14.3 or newer, below 10.0 |
| Spotless Gradle plugin | 8.0.0 | 8.x |
| JDK running Gradle | 17 | 17 or newer, supported by your Gradle and formatter versions |

Versions outside these ranges stop with an error before anything is formatted; the error shows the detected
version and the accepted range. Versions inside the ranges but missing from the [tested combinations](#tested-combinations)
run with a warning.

- The JDK requirement applies to the **JVM running Gradle**, not your source level or compilation toolchain.
  Check `JAVA_HOME` for terminal and hook calls, **Gradle JVM** in IntelliJ, and `org.gradle.java.home`
  or daemon JVM criteria if you set them.
- Formatters can need a newer JDK than Bridge. For example, Google Java Format 1.30.0 requires JDK 21.
  Bridge keeps the formatter version your project chose and reports the incompatibility instead of downgrading it.
- Bridge formats **whole Java files in Gradle projects**. Maven support is in progress; other languages are not supported.
- CI runs on Linux and macOS. The Windows launcher is included but not yet verified.
- Not supported: Gradle configuration cache, isolated projects, Spotless `ratchetFrom`, and non-serializable formatting steps.
  If your build enables the configuration cache, run Bridge tasks with `--no-configuration-cache`.
- Bridge relies on Spotless internal APIs, so a Spotless upgrade may need a matching Bridge update.
- Use Bridge only in projects you trust. Like any Gradle build, the daemon runs your build configuration and
  formatter code. Its state directory is private to your user, and requests are authenticated with a per-daemon token.

### Tested combinations

Bridge 0.1.1 is tested with:

| JDK | Gradle | Spotless verification |
| --- | --- | --- |
| 17 | 8.14.3 | 8.10.2 full regression suite; 8.0.0 version-switch and output-parity check |
| 17 | 8.14.6 | Same checks |
| 17 | 9.0.0 | Same checks |
| 17 | 9.8.1 | Same checks |
| 21 | 8.14.3 | Same checks |
| 21 | 8.14.6 | Same checks |
| 21 | 9.0.0 | Same checks |
| 21 | 9.8.1 | Same checks |
| 25 | 9.8.1 | Same checks |

The suite covers Groovy and Kotlin DSL, output parity with plain Spotless, Palantir, Google, and Eclipse formatters,
new and excluded files, configuration refresh, shared daemon reuse, concurrent requests, and hooks running without
an IDE. CI runs every combination on Ubuntu and two of them on macOS. JDK 25 has not been tested with Gradle 8.x or 9.0.0.

## Configuration changes

- Changes to build scripts, Gradle configuration, wrapper files, and formatting configuration such as
  `.editorconfig` are detected automatically. If you change environment variables or inputs outside the
  watched paths, run `spotlessBridgeStart` to reload. Extra watch paths configured in IntelliJ
  apply only to that IDE client.
- Use the same Gradle properties and environment for IDE and hook requests. Clients of the same
  Gradle root share the most recently loaded configuration.
- Excluded files are left unchanged. Files that aren't known targets trigger Gradle evaluation, and
  passing excluded files repeatedly triggers it every time, so pass only the Java files you intend to format.
  To format generated files, run source generation first.

## Troubleshooting

You normally don't need to manage the daemon. To check it, run `./gradlew spotlessBridgeStatus` from the Gradle root.
It prints `Spotless Bridge pid=<number>`, or `-1` when no daemon is running. To confirm that IntelliJ and your hook
share a daemon, format a file from each and check that the PID stays the same.

Runtime files and logs are stored in your project's `.gradle/spotless-bridge/`; keep this directory out of Git.
If a request fails, check `daemon.log`, `worker.log`, and `gradle-start-*.log` there.
Run `./gradlew spotlessBridgeStop` before deleting the directory.

Concurrent requests from the IDE and hooks are processed one at a time. A configuration change reloads
the formatter worker; a change to the engine or the JDK also replaces the daemon.

## Release notes

See [CHANGELOG.md](CHANGELOG.md) for changes in each version and how to upgrade.

## Contributing

To build Bridge from source, run its tests, or try unreleased changes, see [Install from source](docs/install-from-source.md).
Implementation and release notes are in the [maintainer notes (Korean)](docs/maintenance.md).

## License

[MIT](LICENSE). Spotless and formatter libraries selected by the consuming project retain their own licenses.
