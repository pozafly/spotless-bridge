# Install Spotless Bridge from source

English | [한국어](install-from-source.ko.md)

This guide is for trying Spotless Bridge before it is published to the Gradle Plugin Portal and
JetBrains Marketplace, or for working on Bridge itself. For everyday usage, see the [README](../README.md).

You need a Spotless Bridge source checkout and **JDK 21**. The examples use these placeholder paths:

- `/path/to/spotless-bridge`: your Spotless Bridge checkout
- `/path/to/jdk21`: a JDK 21 installation

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

Build the IDE plugin ZIP from the checkout:

```sh
JAVA_HOME=/path/to/jdk21 ./gradlew pluginZip
```

The IDE adapter uses IntelliJ IDEA **2026.2.3 (IU-262.10968.63)**, pinned in `gradle.properties`.
The default SDK location is `/Applications/IntelliJ IDEA.app/Contents`. `verifyIdeaSdk` rejects a different
product, version, or build before compilation, even if you override the location. Use the complete IDE
distribution including its JBR: this SDK contains Java 25 classes, so IDE compilation and tests use its
bundled JBR while Gradle itself runs on JDK 21. Adapter bytecode remains Java 21; the engine remains Java 17.
If IntelliJ is installed elsewhere on macOS, add `-PideaHome=/path/to/IntelliJ/Contents`.
The ZIP declares IntelliJ IDEA builds `262.10968.63` through `262.*`; only the pinned SDK has been verified.
A later 262 build still needs verification before it is added to the tested list.

In IntelliJ, open **Settings → Plugins → gear menu → Install Plugin from Disk…** and select
`build/distributions/spotless-bridge-0.1.1.zip`. If you installed an earlier development build,
disable or uninstall it first. Then follow [IntelliJ IDEA setup](../README.md#intellij-idea-preview) in the README.

### Verify the IntelliJ adapter without publishing

```sh
JAVA_HOME=/path/to/jdk21 ./gradlew verifyIdeaPlugin -PideaHome=/path/to/pinned/IDE
```

This runs SDK validation, the Safe Mode regression tests, ZIP packaging and Plugin Verifier **1.410**.
Reports are under `build/reports/plugin-verifier/` and `build/reports/tests/ideaTest/`.
Binary compatibility errors, invalid plugins and forbidden API usage fail the task. Warnings remain
visible in the report; a successful verification is not Marketplace approval or an IDE UI smoke test.
The CI has a separate Ubuntu IDE job which downloads the pinned SDK and checks its SHA-256. The
existing 11 engine jobs do not need an SDK. Neither verification task uploads anything.

Formatting is unavailable in Safe Mode. Trust the project through IntelliJ's own UI before enabling it;
the plugin never grants trust automatically. The check runs again before using a cached formatter.
The tests mock the IDE trust response to check request gating; manually test the actual Safe Mode UI,
Reformat Code, save actions and shared daemon PID in a clean IDE before release.

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
The engine and engine tests compile with `--release 17`; the IntelliJ adapter and its tests use the pinned SDK JBR with `--release 21`.
Selecting an external JDK 17 for formatting does not change IntelliJ's own runtime requirements.

The IntelliJ SDK is needed for IDE packaging and verification tasks, but not engine checks. For implementation details, regression checks, and
publishing instructions, see the [maintainer notes (Korean)](maintenance.md).
