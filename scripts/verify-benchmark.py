#!/usr/bin/env python3
"""Reproduce the previous three-step Gradle benchmark against the dynamic packaged engine."""
from pathlib import Path
import json, os, shutil, subprocess, sys, tempfile

root = Path(__file__).resolve().parent.parent
baseline, java_home = Path(sys.argv[1]).resolve(), Path(sys.argv[2]).resolve()
env = dict(os.environ, JAVA_HOME=str(java_home))
version = next(line.split('=', 1)[1] for line in (root / 'gradle.properties').read_text().splitlines() if line.startswith('version='))
subprocess.run([str(root / 'gradlew'), '--console=plain', 'testClasses', 'publishAllPublicationsToTestRepository'], cwd=root, env=env, check=True)
with tempfile.TemporaryDirectory(prefix='dynamic-spotless-benchmark-') as temp:
    temp = Path(temp)
    fixture = temp / 'fixture'
    (fixture / 'src/main/java').mkdir(parents=True)
    shutil.copytree(root / 'gradle', fixture / 'gradle')
    (fixture / 'settings.gradle').write_text("pluginManagement { repositories { maven { url = uri('" + (root / 'build/repository').as_uri() + "') }; gradlePluginPortal() } }\nrootProject.name = 'benchmark'\n")
    (fixture / 'build.gradle').write_text("plugins { id 'java'; id 'com.diffplug.spotless' version '8.10.2'; id 'io.github.pozafly.spotlessdaemon' version '" + version + "' }\n"
        "repositories { mavenCentral() }\n"
        "spotless { java { shortenFullyQualifiedTypes(); removeUnusedImports(); palantirJavaFormat('2.97.0') } }\n")
    for i, sample in enumerate(json.loads((baseline / 'samples.json').read_text())):
        shutil.copy2(sample['input'], fixture / f'src/main/java/Sample{i}.java')
        shutil.copy2(baseline / f'gradle-output-{i}.java', fixture / f'expected{i}.txt')
    classpath = os.pathsep.join(str(root / p) for p in ['build/classes/java/main', 'build/classes/java/test'])
    result = subprocess.run([str(java_home / 'bin/java'), '-cp', classpath, 'io.github.pozafly.spotlessdaemon.DynamicBenchmark',
        str(fixture)],
        cwd=root, env=env, capture_output=True, text=True)
    (root / 'build/dynamic-benchmark.txt').write_text(result.stdout + result.stderr)
    print(result.stdout, end=''); print(result.stderr, end='', file=sys.stderr)
    result.check_returncode()
