# Install Spotless Bridge from source

English | [한국어](install-from-source.ko.md)

This guide is for trying Spotless Bridge before it is published to the Gradle Plugin Portal and
JetBrains Marketplace, or for working on Bridge itself. For everyday usage, see the [README](../README.md).

You need a Spotless Bridge source checkout and **JDK 21**. The examples use these placeholder paths:

- `/path/to/spotless-bridge`: your Spotless Bridge checkout
- `/path/to/jdk21`: a JDK 21 installation

## Native CLI build prerequisites (0.2.0)

Install Rust via rustup before building from source. `native/rust-toolchain.toml` pins Rust 1.90.0;
Cargo builds the host CLI automatically when Gradle processes resources. The native client has no external crates.
This prerequisite applies only to Bridge contributors: users of a published plugin do not install Rust.
Use version `0.2.1` in your consumer when testing this checkout's local repository.

```sh
./gradlew testNativeCli nativeSmokeTest
```

Source builds package the host executable only. To assemble a release from the six tested CI artifacts,
place their platform directories under `build/native` and pass `-PnativeCliDirectory=build/native`.
`publishPlugins` (including `--validate-only`) rejects packages missing any of the six clients.
Do not run `clean` after downloading those build artifacts.

## 1. Publish the Gradle plugin to a local repository

From the Spotless Bridge checkout:

```sh
cd /path/to/spotless-bridge
export JAVA_HOME=/path/to/jdk21
./gradlew publishAllPublicationsToTestRepository
```

The plugin is published to `build/repository`. Add that path to the beginning of your project's `settings.gradle`:

```groovy
pluginManagement {
    repositories {
        maven { url = uri('/path/to/spotless-bridge/build/repository') }
        gradlePluginPortal()
    }
}
```

In `settings.gradle.kts`, use double quotes instead of single quotes.
Then apply the plugin and connect your AI tool as described in the README's [Quick start](../README.md#quick-start).
The hook launcher appears in `.gradle/spotless-bridge/` on the next Gradle run; there is nothing else to install.
Once the plugin is on the Plugin Portal, remove the local `maven` entry.

## 2. Build and install the IntelliJ plugin

Build with JDK 21 and the **2025.3 (IU-253.28294.334)** SDK, the minimum supported IDE.
Pinned versions, exact build numbers and Linux archive SHA-256 hashes are in
[`gradle/idea-compatibility.json`](../gradle/idea-compatibility.json). The first entry is the build SDK.
Download `https://download.jetbrains.com/idea/idea-<version>.tar.gz`, verify its hash against that file,
and extract it with `--strip-components=1` into `<sdk-root>/<version>`.
The default SDK root is `.gradle/idea-sdks` inside this checkout; SDK archives are not committed.
The Linux distributions can be used as SDK inputs on macOS too: compilation and tests run on a local
JDK 21, while Plugin Verifier reads the IDE's classes and bundled runtime without launching the IDE.

```sh
JAVA_HOME=/path/to/jdk21 ./gradlew ideaTest pluginZip -PideaSdkRoot=/path/to/sdk-root
```

Only the 2025.3 SDK is needed to build. `-PideaHome=/path/to/IDE` can override its location
(use the `Contents` directory for a macOS IDE). `verifyIdeaSdk` rejects any other product, version or build.
Adapter bytecode is Java 21; the engine remains Java 17.
The ZIP declares builds `253.28294.334` through `262.*` (2025.3–2026.2).

In IntelliJ, open **Settings → Plugins → gear menu → Install Plugin from Disk…** and select
`build/distributions/spotless-bridge-0.2.1.zip`. If you installed an earlier development build,
disable or uninstall it first. Then follow [IntelliJ IDEA setup](../README.md#intellij-idea-preview).

### Verify the same ZIP across IDE versions without publishing

Install all six pinned SDKs under the SDK root, then run:

```sh
JAVA_HOME=/path/to/jdk21 ./gradlew verifyIdeaPlugin -PideaSdkRoot=/path/to/sdk-root
```

This runs the Safe Mode regression tests, builds one ZIP with 2025.3, and checks that ZIP with
Plugin Verifier **1.410** against the first release and pinned latest patch of each supported series:

| IntelliJ IDEA | Build           |
| ------------- | --------------- |
| 2025.3        | `253.28294.334` |
| 2025.3.6.1    | `253.33813.55`  |
| 2026.1        | `261.22158.277` |
| 2026.1.5      | `261.27258.48`  |
| 2026.2        | `262.8665.258`  |
| 2026.2.3      | `262.10968.63`  |

On October 16, 2026, the 0.1.1 ZIP passed all six checks above. The 2025.3 builds reported `Compatible`;
the 2026.1 and 2026.2 builds reported `Compatible` with three deprecated API usages, explained below.

To verify an already built ZIP against one pinned version without compiling it again:

```sh
JAVA_HOME=/path/to/jdk21 ./gradlew verifyIdeaPlugin \
  -PideaPluginZip=/absolute/path/spotless-bridge-0.2.1.zip \
  -PideaVerifyVersion=2026.2.3 -PideaSdkRoot=/path/to/sdk-root
```

Only that version's SDK is needed for this command. Reports are under `build/reports/plugin-verifier/`;
`plugin-sha256.txt` records the verified ZIP hash. Missing SDKs, incorrect build numbers, missing verdicts,
binary compatibility errors and forbidden API usage fail verification. The task also checks that the ZIP
has not changed during verification. Newer IDEs report deprecated usages of the public `ReadAction.compute/run`
API; these are retained for 2025.3 compatibility because the `*Blocking` replacements do not exist there.
Warnings stay visible; no APIs are accessed through reflection to hide compatibility problems.

CI builds the ZIP once, then six verifier jobs download the same artifact and check its SHA-256 before
and after verification. SDK downloads are also hash-checked. The existing 11 engine jobs remain independent
of IDE SDKs. The CI does not publish to Marketplace.

Safe Mode blocks formatting, including cached clients. Trust must be granted through IntelliJ's UI.
The regression tests mock the trust response. Binary verification does not replace testing actual Safe Mode,
Reformat Code, save actions and shared daemon PID in real IDEs. Intermediate patches and future releases
have not each been tested; update the pinned matrix when widening the verified list.

## Development

Run the engine and Gradle plugin checks with JDK 21:

```sh
JAVA_HOME=/path/to/jdk21 ./gradlew check engineDist
```

To run the same suite against a different consumer Gradle version (the default is `8.14.3`):

```sh
JAVA_HOME=/path/to/jdk21 ./gradlew check engineDist -PtestGradleVersion=9.8.1
```

To compile with JDK 21 and run the full compatibility suite on JDK 17:

```sh
JAVA_HOME=/path/to/jdk21 ./gradlew check engineDist \
  -PtestGradleVersion=8.14.3 -PtestJavaHome=/path/to/jdk17
```

For JDK 25, use Gradle 9.8.1:

```sh
JAVA_HOME=/path/to/jdk21 ./gradlew check engineDist \
  -PtestGradleVersion=9.8.1 -PtestJavaHome=/path/to/jdk25
```

`testJavaHome` defaults to the build JVM's home. It also sets `JAVA_HOME` for the hook's first run,
but it doesn't change your system default Java or the engine's minimum runtime version.

To run the suite against another consumer Spotless version (the default is `8.10.2`), add
`-PtestSpotlessVersion=<version>`. CI also runs two combinations on macOS, and a weekly workflow runs the suite
against the latest Spotless release to catch changes in the Spotless internal APIs Bridge relies on.

Bridge itself is built with JDK 21 and the checked-in Gradle 8.14.3 wrapper. Tests run the same
Java 17-targeted engine on the selected consumer JDK, which covers Gradle, the daemon, workers, and hooks.
Ubuntu CI runs four Gradle versions each on JDK 17 and 21, plus JDK 25 with Gradle 9.8.1: the nine combinations
in the [compatibility table](../README.md#requirements-and-compatibility).
Google Java Format is pinned to 1.28.0 in JDK 17 fixtures and 1.30.0 in JDK 21/25 fixtures.
The engine and engine tests compile with `--release 17`; the IntelliJ adapter and its tests use JDK 21 with `--release 21`.
Selecting an external JDK 17 for formatting does not change IntelliJ's own runtime requirements.

The IntelliJ SDK is needed for IDE packaging and verification tasks, but not engine checks. For implementation details, regression checks, and
publishing instructions, see the [maintainer notes (Korean)](maintenance.md).
