# Changelog

All notable changes to Spotless Bridge are documented here. The release workflow publishes the section
for each `v<version>` tag as its GitHub Release notes.

## [0.1.1]

First release published to the Gradle Plugin Portal.

### Added

- `spotlessBridgeInstallHook` writes the AI hook launchers (`spotless-bridge` for macOS and Linux,
  `spotless-bridge.cmd` for Windows) and their license into `tools/spotless-bridge/`.
- The launcher prepares the engine again when it is missing, for example after part of `.gradle` was removed.
- Hook errors are reported in one line, such as `Spotless Bridge: file not found: <path>`, with exit code 1.
- `spotlessBridgeStart` warns when an installed launcher differs from the one in the plugin, or when the
  0.1.0 `ai-hook.py` is still present.
- Gradle and Spotless version checks: Gradle 8.14.3 or newer and below 10.0, and Spotless 8.x, are accepted.
  Versions outside the verified list warn; versions outside the range stop with the detected version and the accepted range.
- Descriptions for `spotlessBridgeStatus` and `spotlessBridgeStop`.

### Changed

- **The AI hook no longer needs Python.** The Python `ai-hook.py` is replaced by the launchers above,
  and the hook logic moved into the engine. Hook commands change from `python3 tools/spotless-bridge/ai-hook.py`
  to `tools/spotless-bridge/spotless-bridge`.
- **Gradle can run on JDK 17.** The minimum was JDK 21. Formatters may still require a newer JDK.
- Error messages and the IntelliJ settings page are in English.

### Upgrading from 0.1.0

Run `./gradlew spotlessBridgeInstallHook`, point your AI hook at `tools/spotless-bridge/spotless-bridge`, and delete
`ai-hook.py`. The next hook call prepares the new engine through Gradle once.

## [0.1.0]

Initial GitHub release with the Gradle plugin, the IntelliJ adapter, and a Python AI hook script.
It was not published to the Gradle Plugin Portal.

[0.1.1]: https://github.com/pozafly/spotless-bridge/releases/tag/v0.1.1
[0.1.0]: https://github.com/pozafly/spotless-bridge/releases/tag/v0.1.0
