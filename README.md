# Spotless Bridge

English | [한국어](README.ko.md)

A persistent Java formatter that uses your project's existing Spotless configuration and libraries.
**IntelliJ and AI hooks share one daemon per Gradle project.** Hooks can start and reuse it while
IntelliJ is closed. No global installation or operating system service is required.

**Status:** `0.5.0` is a development version. It has not been published to the Gradle Plugin Portal
or JetBrains Marketplace. There is no public release/tag for this version yet; use the local
installation below with a source checkout containing these changes.

## Requirements and support

- JDK 21 and the consuming project's Gradle wrapper.
- Python 3 for the recommended AI hook; it uses only the standard library.
- Automated tests use Gradle 8.14.3, Spotless 8.10.2/8.0.0, and Palantir, Google, and Eclipse formatters.
  These are tested combinations, not a guarantee for all Spotless versions or custom steps.
- Local verification has used macOS. The CI workflow targets Linux; Windows and Gradle 9 are not verified.
- Java whole-file formatting only. Maven, other languages, and range formatting are not supported.
- Gradle configuration cache and isolated projects are explicitly declared unsupported.

Spotless Bridge does not select your Spotless or formatter versions. Keep them in your own build.

## Project setup

Apply Spotless Bridge once in the root `build.gradle`. Keep each module's existing Spotless configuration.
The following formatter versions and rules are examples:

```groovy
plugins {
    id 'java'
    id 'com.diffplug.spotless' version '8.10.2'
    id 'io.github.pozafly.spotlessbridge' version '0.5.0'
}
repositories { mavenCentral() }
spotless {
    java {
        shortenFullyQualifiedTypes()
        removeUnusedImports()
        palantirJavaFormat('2.97.0')
    }
}
```

For Kotlin DSL, use `id("io.github.pozafly.spotlessbridge") version "0.5.0"` in `plugins`.

### Local installation before publication

In this source checkout, run with JDK 21:

```sh
./gradlew publishAllPublicationsToTestRepository
```

This writes the implementation JAR and plugin marker to `build/repository` only. It does not publish
to the Portal or install into a global Maven repository. Add this local path to the consuming
project's `settings.gradle`, before other blocks:

```groovy
pluginManagement {
    repositories {
        maven { url = uri('/path/to/spotless-bridge/build/repository') }
        gradlePluginPortal()
    }
}
```

Use double quotes instead of single quotes in `settings.gradle.kts`. Replace the path with the
actual source checkout location. After Portal publication, the local Maven entry can be removed.
Public repositories cannot resolve `0.5.0` yet.

## Install the AI hook in your project

The Gradle plugin does **not** automatically copy the Python hook into your project.
For now, copy it from the local source checkout containing these changes.

After public releases are available, download the source archive for the `v<version>` release
matching your Gradle plugin version from the [repository's releases](https://github.com/pozafly/java-formatter-server/releases).
For example, plugin `0.5.0` should use source tag `v0.5.0`. That release/tag does not exist yet.
Use a fixed release rather than the moving `main` branch.

From your consuming project's Gradle root, copy the script and its license:

```sh
bridge_source=/path/to/spotless-bridge
mkdir -p tools/spotless-bridge
cp "$bridge_source/scripts/ai-hook.py" tools/spotless-bridge/ai-hook.py
cp "$bridge_source/LICENSE" tools/spotless-bridge/LICENSE
```

Commit these two copied files in the consuming project. Keep the license next to the script;
do not replace your project's own license. No `pip` installation is needed.

Configure your AI tool's completion hook to run the following command from the Gradle root,
passing each existing changed Java file as a separate argument:

```sh
export JAVA_HOME=/path/to/jdk21
python3 tools/spotless-bridge/ai-hook.py "$PWD" src/main/java/Changed.java
```

Replace the JDK path and file list with your actual values. Quote paths containing spaces or commas.
From another working directory, use absolute paths for the script and Gradle root. File arguments
may be absolute or relative to the Gradle root.

The caller supplies the changed-file list; the script does not discover changes. No file arguments
means a successful no-op, not whole-project formatting. Exclude deleted files: nonexistent paths fail.
Propagate a nonzero exit code as a failed hook. Update the copied script together with the Gradle
plugin version when upgrading.

On first use, the hook runs Gradle to install the project-selected engine. Later calls read
`.gradle/spotless-bridge/client-runtime.txt` and invoke the Java client directly. Files already registered
as Spotless targets reuse a running daemon without Gradle when configuration is unchanged. A missing
daemon, configuration change, or unknown target triggers Gradle startup or refresh. Excluded files are
left unchanged, but passing them to repeated hook invocations currently reevaluates Gradle each time.
IntelliJ does not need to be running.

You can also use Gradle directly. Create a UTF-8 text file containing one path per line, relative
to the Gradle root or absolute:

```sh
./gradlew --no-configuration-cache spotlessBridgeApply -PspotlessBridgeFiles=/path/to/changed-files.txt
```

This evaluates Gradle on every invocation. Omitting the file-list property fails instead of applying
to all sources. For frequent hooks, prefer the installed Python script.

If you also use OpenRewrite, run it before formatting, then run `spotlessCheck`, compilation, and
the tests required by your project. A successful formatter invocation does not prove tests passed.

## IntelliJ adapter

Build the adapter with JDK 21 and an IntelliJ SDK:

```sh
JAVA_HOME=/path/to/jdk21 ./gradlew clean build pluginZip
```

The plugin descriptor permits IntelliJ IDEA builds from `262.10968` through `262.*`; earlier 262
builds are not included. The ZIP build currently expects the macOS application layout; override the
application path with `-PideaHome=/path/to/IntelliJ/Contents` if needed. The SDK is not needed for
the Gradle plugin or headless checks.

`build/distributions/spotless-bridge-0.5.0.zip` contains only the IDE adapter. The consuming project's
Gradle plugin selects the daemon engine version.

1. Disable or uninstall the previous development plugin before installing this ZIP.
2. Open Settings → Plugins → gear menu → Install Plugin from Disk… and select the ZIP.
3. Open Settings → Tools → Spotless Bridge, enable it, and specify JDK 21.
4. Set the Gradle root and additional watched paths if necessary.
5. Under Tools → Actions on Save → Reformat code, select Java / Whole file.
   Disable overlapping Java formatters and Optimize imports, Rearrange code, and Code cleanup.

The renamed plugin has a new plugin ID and settings storage name; reapply settings when replacing
the older development build. Do not enable both adapters together.

The IDE adapter only accepts writable local Java files that IntelliJ recognizes as source content.
A Java file outside those source roots can be a Spotless target for the hook while remaining ineligible
for IDE formatting. The adapter skips quick-format and whitespace-only requests.

Reformat Code and format-on-save send document contents to the daemon and apply results only if the
document is still current. IDE results use LF; disk formatting through hooks follows Spotless's encoding
and line-ending policy. Closing the IDE or disabling the adapter closes its connection, not the daemon.

## Daemon lifecycle

One daemon belongs to each canonical Gradle root. It owns one worker using that project's formatter
libraries. IDE and hook requests share both processes and are handled sequentially.

- Unchanged configuration reuses the daemon and worker.
- Changed settings or targets replace the worker inside the same daemon.
- Changed engine JAR or execution JDK replaces the daemon too.
- Gradle tasks export configuration directly; the daemon does not recursively invoke Gradle.

```sh
./gradlew spotlessBridgeStart    # Start or refresh with current settings
./gradlew spotlessBridgeStatus   # PID, or -1 when not running
./gradlew spotlessBridgeStop     # Explicit shutdown
```

The daemon and worker exit after 30 idle minutes. File locks coordinate concurrent startup; stale
connection information is replaced on the next startup. Communication uses loopback TCP and a
project-specific authentication token, not a public network listener.

State and logs live under `.gradle/spotless-bridge/`: `endpoint.properties`, `daemon.log`, `worker.log`,
`gradle-start-*.log`, `client-runtime.txt`, `snapshots/`, and `runtime/`. Do not commit this directory.
Run `spotlessBridgeStop` before deleting it.

## Change detection and limitations

The client watches build scripts, Gradle properties and wrapper files, `gradle/`, `buildSrc/`,
`build-logic/`, TOML, `.editorconfig`, `.gitattributes`, included builds, external projects, Gradle user
home initialization files. Additional watch paths configured in IntelliJ apply only to that IDE client;
they are not shared with the hook. When a hook depends on an input outside its watched paths, run
`spotlessBridgeStart` explicitly after changing that input. Missing files are not cached as excluded,
so a newly saved file can be evaluated again.

Not every Gradle input can be tracked, including environment variables and remote data. Refresh
explicitly with `spotlessBridgeStart` when such inputs change. Tools using different `-P` options
share the most recently refreshed configuration; align properties and environments for one project.

- Spotless internal APIs and serialization are used. Arbitrary versions or custom steps may fail.
- `ratchetFrom` and non-serializable steps are unsupported. Failures do not silently reuse old settings.
- Configuration evaluation has a 120-second timeout, worker formatting 15 seconds, and TCP response
  waiting 30 seconds. UTF-8 messages are limited to 16 MiB.
- Hooks fail before writing when detected file/configuration changes invalidate the result. Atomic
  locking against external editors is not guaranteed.
- Run source-generation tasks first for generated sources outside the default discovery policy.
- Build and formatter processes are not sandboxed; use trusted project builds.

## Development and verification

```sh
JAVA_HOME=/path/to/jdk21 ./gradlew check engineDist
```

`check` publishes to a local file repository and tests the actual plugin in isolated consumer builds.
It covers Groovy/Kotlin DSL, multiple modules, formatter parity, new/excluded files, configuration
changes and recovery, shared daemon reuse, concurrent requests, restart/shutdown, project-local hook
installation, and zero Gradle invocations for repeated hooks on registered targets with unchanged configuration.

`gradle.properties` owns the plugin version. Tests receive that version from Gradle rather than
hardcoding it. Real IDE UI behavior, Plugin Verifier, and Marketplace review are separate checks.
Normal builds and tests do not publish publicly. See the [maintainer notes (Korean)](docs/maintenance.md).

## License

[MIT](LICENSE). Spotless and formatter libraries selected by the consuming project retain their own licenses.
