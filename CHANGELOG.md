# Changelog

All notable changes to Spotless Bridge are documented here. The release workflow publishes the section
for each `v<version>` tag as its GitHub Release notes.

## [0.1.1]

First release published to the Gradle Plugin Portal.

### Added

- The plugin writes an AI hook launcher to `.gradle/spotless-bridge/` (`spotless-bridge` for macOS and Linux,
  `spotless-bridge.cmd` for Windows) on every Gradle run. Nothing has to be installed or committed.
- The launcher prepares the engine again when it is missing, for example after part of `.gradle` was removed.
- Hook errors are reported in one line, such as `Spotless Bridge: file not found: <path>`, with exit code 1.
- Gradle and Spotless version checks: Gradle 8.14.3 or newer and below 10.0, and Spotless 8.x, are accepted.
  Versions outside the verified list warn; versions outside the range stop with the detected version and the accepted range.
- `spotlessBridgeApply` without arguments formats every Spotless Java target, matching `spotlessApply`.
  `-PspotlessBridgeFiles` also accepts comma-separated paths, and the task reports how many files changed.
- Bridge tasks are grouped as `spotlessBridge` in `./gradlew tasks`, with a description for each task.

### Changed

- **The AI hook no longer needs Python or a copied script.** The hook command changes from
  `python3 tools/spotless-bridge/ai-hook.py <root> <files>` to `.gradle/spotless-bridge/spotless-bridge <files>`,
  and the hook logic moved into the engine.
- **Gradle can run on JDK 17.** The minimum was JDK 21. Formatters may still require a newer JDK.
- Error messages and the IntelliJ settings page are in English.

### Upgrading from 0.1.0

Point your AI hook at `.gradle/spotless-bridge/spotless-bridge` and delete `tools/spotless-bridge/`.
The launcher appears after the next Gradle run, and its first call prepares the new engine through Gradle once.

## [0.1.0]

Initial GitHub release with the Gradle plugin, the IntelliJ adapter, and a Python AI hook script.
It was not published to the Gradle Plugin Portal.

[0.1.1]: https://github.com/pozafly/spotless-bridge/releases/tag/v0.1.1
[0.1.0]: https://github.com/pozafly/spotless-bridge/releases/tag/v0.1.0
