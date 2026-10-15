#!/usr/bin/env python3
"""Format explicitly supplied files through the project's Gradle-owned Spotless daemon."""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('project', type=Path, help='Gradle root with io.github.pozafly.spotlessdaemon applied')
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
        runtime = root / '.gradle/spotless-daemon/client-runtime.txt'
        if not runtime.is_file():
            # First use downloads the project-selected engine. Subsequent invocations start the client directly.
            bootstrap = subprocess.run([str(wrapper), '--console=plain', '--no-configuration-cache',
                                        'spotlessDaemonStart'], cwd=root, timeout=180)
            if bootstrap.returncode:
                return bootstrap.returncode
        jar, java_home = runtime.read_text(encoding='utf-8').splitlines()
        java = Path(java_home) / 'bin' / ('java.exe' if os.name == 'nt' else 'java')
        return subprocess.run([str(java), '-cp', jar, 'io.github.pozafly.spotlessdaemon.GradleFormatter',
                               str(root), str(selected)], cwd=root, timeout=180).returncode



if __name__ == '__main__':
    raise SystemExit(main())
