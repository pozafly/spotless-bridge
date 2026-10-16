#!/usr/bin/env python3
"""Format explicitly supplied files through the project's Gradle-owned Spotless daemon."""
import argparse
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import zipfile

# Replaced with the plugin version when the script is packaged; spotlessBridgeInstallHook writes the packaged copy.
BRIDGE_VERSION = '@spotlessBridgeVersion@'


def warn_if_outdated(jar: str) -> None:
    """Warn when this script and the project's engine come from different Spotless Bridge versions."""
    if BRIDGE_VERSION.startswith('@'):
        return
    try:
        with zipfile.ZipFile(jar) as engine:
            packaged = engine.read('spotless-bridge/ai-hook.py').decode('utf-8')
    except (OSError, KeyError, zipfile.BadZipFile):
        return  # Older engines do not package the hook script.
    match = re.search(r"^BRIDGE_VERSION = '([^']*)'$", packaged, re.MULTILINE)
    if match and match.group(1) != BRIDGE_VERSION:
        print(f'Spotless Bridge: this hook script is {BRIDGE_VERSION} but the project uses {match.group(1)}. '
              'Run ./gradlew spotlessBridgeInstallHook to update it.', file=sys.stderr)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('project', type=Path, help='Gradle root with io.github.pozafly.spotlessbridge applied')
    parser.add_argument('files', type=Path, nargs='*', help='Changed files, relative to the Gradle root or absolute')
    args = parser.parse_args()
    root = args.project.resolve(strict=True)
    if not args.files:
        return 0
    files = [(root / path).resolve(strict=True) for path in args.files]
    if any('\n' in str(path) or '\r' in str(path) for path in files):
        parser.error('Newlines in file names are not supported')
    # The list avoids shell expansion and handles spaces/commas in paths. No global installation is needed.
    with tempfile.TemporaryDirectory(prefix='spotless-hook-') as temporary:
        selected = Path(temporary) / 'files.txt'
        selected.write_text(''.join(str(path) + '\n' for path in files), encoding='utf-8')
        wrapper = root / ('gradlew.bat' if os.name == 'nt' else 'gradlew')
        runtime = root / '.gradle/spotless-bridge/client-runtime.txt'
        if not runtime.is_file():
            # First use downloads the project-selected engine. Subsequent invocations start the client directly.
            bootstrap = subprocess.run([str(wrapper), '--console=plain', '--no-configuration-cache',
                                        'spotlessBridgeStart'], cwd=root, timeout=180)
            if bootstrap.returncode:
                return bootstrap.returncode
        jar, java_home = runtime.read_text(encoding='utf-8').splitlines()
        warn_if_outdated(jar)
        java = Path(java_home) / 'bin' / ('java.exe' if os.name == 'nt' else 'java')
        return subprocess.run([str(java), '-cp', jar, 'io.github.pozafly.spotlessbridge.GradleFormatter',
                               str(root), str(selected)], cwd=root, timeout=180).returncode



if __name__ == '__main__':
    raise SystemExit(main())
