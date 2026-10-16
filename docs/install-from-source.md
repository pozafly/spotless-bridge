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
Then apply the plugin as described in the README's [Quick start](../README.md#quick-start).
Once the plugin is on the Plugin Portal, remove the local `maven` entry.

## 2. Install the AI hook script

After applying the locally published plugin, let it write the hook script and its license
from your project's Gradle root:

```sh
./gradlew spotlessBridgeInstallHook
```

Commit both files. When you update the checkout and republish the plugin, run the task again so
that the script matches the plugin. To connect the script to your AI tool, follow
[Connect your AI tool](../README.md#3-connect-your-ai-tool) in the README.

## 3. Build and install the IntelliJ plugin

Build the IDE plugin ZIP from the checkout:

```sh
JAVA_HOME=/path/to/jdk21 ./gradlew pluginZip
```

The build uses the IntelliJ SDK at `/Applications/IntelliJ IDEA.app/Contents`.
If IntelliJ is installed elsewhere on macOS, add `-PideaHome=/path/to/IntelliJ/Contents`.
The ZIP supports IntelliJ IDEA builds `262.10968` through `262.*`.

In IntelliJ, open **Settings → Plugins → gear menu → Install Plugin from Disk…** and select
`build/distributions/spotless-bridge-0.1.1.zip`. If you installed an earlier development build,
disable or uninstall it first. Then follow [IntelliJ IDEA setup](../README.md#intellij-idea-preview) in the README.

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

Bridge itself is built with JDK 21 and the checked-in Gradle 8.14.3 wrapper. Tests run the same
Java 17-targeted engine on the selected consumer JDK, which covers Gradle, the daemon, workers, and hooks.
Ubuntu CI is configured for four Gradle versions each on JDK 17 and 21, plus JDK 25 with Gradle 9.8.1: nine combinations
from the [compatibility table](../README.md#requirements-and-compatibility). All nine were checked locally on macOS
(Zulu 17.0.20.1, Corretto 21.0.6, and Temurin 25.0.4.1); GitHub CI still needs to run for these changes.
Google Java Format is pinned to 1.28.0 in JDK 17 fixtures and 1.30.0 in JDK 21/25 fixtures.
The engine and tests compile with `--release 17`; the IntelliJ adapter keeps `--release 21` for its IDE SDK.
Selecting an external JDK 17 for formatting does not change IntelliJ's own runtime requirements.

The IntelliJ SDK is needed only for `pluginZip`. For implementation details, regression checks, and
publishing instructions, see the [maintainer notes (Korean)](maintenance.md).
