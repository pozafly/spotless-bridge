package io.github.pozafly.spotlessbridge;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Real isolated Gradle builds; never changes an application repository or IDE settings. */
public final class FormatterSelfTest {
    private static final String SOURCE =
            "import java.util.Set;\n// TOKEN\nclass Sample{java.util.List<String> names;String label=\"안녕하세요 😀\";}\n";

    private static String pluginRepositories;

    public static void main(String[] args) throws Exception {
        Path engine = Path.of(args[0]);
        pluginRepositories = "pluginManagement { repositories { maven { url = uri('"
                + Path.of(args[1]).toUri() + "') }; gradlePluginPortal() } }\n";
        String bridgeVersion = args[2];
        String gradleVersion = args[3];
        int javaVersion = Runtime.version().feature();
        check(javaVersion >= 17, "Compatibility checks require JDK 17 or newer");
        String googleVersion = javaVersion < 21 ? "1.28.0" : "1.30.0";
        check(Path.of(System.getProperty("java.home")).toRealPath()
                        .equals(Path.of(System.getenv("JAVA_HOME")).toRealPath()),
                "Hook bootstrap JAVA_HOME must match the test runtime");
        System.out.println("Testing Bridge " + bridgeVersion + " with consumer Gradle " + gradleVersion
                + " / JDK " + Runtime.version());
        String version = System.getProperty("spotlessBridge.testSpotlessVersion", "8.10.2");
        Path temp = Files.createTempDirectory("spotless-bridge-test-");
        // These fixed versions exercise both sides of the policy; newer JDKs may not run old Gradle.
        if ((javaVersion == 17 || javaVersion == 21) && gradleVersion.equals("8.14.3"))
            versionCompatibility(temp, engine, bridgeVersion);
        Path root = temp.resolve("fixture");
        Path cache = root.resolve(".gradle/spotless-bridge/snapshots");
        Path file = root.resolve("src/main/java/Sample.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, SOURCE);
        Path excluded = file.resolveSibling("Excluded.java");
        Files.writeString(excluded, SOURCE);
        installWrapper(root, gradleVersion);
        Files.copy(Path.of("gradlew"), root.resolve("gradlew"));
        root.resolve("gradlew").toFile().setExecutable(true);
        Path settings = root.resolve("settings.gradle");
        Files.writeString(settings, pluginRepositories + "rootProject.name = 'formatter-test'\n");
        Path build = root.resolve("build.gradle");
        String initial = """
                plugins { id 'java'; id 'com.diffplug.spotless' version '%s'; id 'io.github.pozafly.spotlessbridge' version '%s' }
                repositories { mavenCentral() }
                spotless { java {
                    target 'src/**/*.java'
                    targetExclude '**/Excluded.java'
                    shortenFullyQualifiedTypes()
                    removeUnusedImports()
                    palantirJavaFormat(providers.gradleProperty('palantirVersion').getOrElse('2.97.0'))
                } }
                apply from: 'format.gradle'
                """.formatted(version, bridgeVersion);
        Files.writeString(build, initial);
        Path script = root.resolve("format.gradle");
        Files.writeString(
                script,
                "spotless { java { replace('marker', 'TOKEN', providers.gradleProperty('marker').getOrElse('FIRST')) } }\n");
        Path props = root.resolve("gradle.properties");
        Files.writeString(props, "marker=FIRST\n");
        Path extra = temp.resolve("external.gradle");
        Files.writeString(extra, "// external configuration\n");
        Path javaHome = Path.of(System.getProperty("java.home"));
        try (GradleFormatter formatter = new GradleFormatter(
                root,
                javaHome,
                List.of(extra),
                () -> System.out.println("Importing fixture Spotless Bridge configuration..."))) {
            String formatted = formatter.format(file, SOURCE);
            checkGolden("palantir", formatted);
            check(formatted.contains("import java.util.List;"), "initial shortening");
            check(!formatted.contains("import java.util.Set;"), "initial unused imports");
            check(formatted.contains("FIRST"), "evaluated property and applied script");
            check(formatted.equals(gradleOutput(root, file, SOURCE)), "initial Gradle byte parity");
            long pid = formatter.workerPid();
            check(formatter.format(file, SOURCE).equals(formatted), "same output");
            check(
                    formatter.importCount() == 1 && formatter.workerPid() == pid,
                    "unchanged configuration reuses worker without Gradle");
            Path snapshot;
            try (var folders = Files.list(cache)) {
                snapshot = folders.filter(p -> Files.isRegularFile(p.resolve("snapshot.properties")))
                        .findFirst()
                        .orElseThrow();
            }
            ProcessSelfTest.main(new String[] {engine.toString(), snapshot.toString(), file.toString()});
            System.out.println("PASS: initial configuration, exact Gradle parity, warm reuse, process lifecycle");
            check(
                    Files.readString(snapshot.resolve("formatter-0/classpath.txt"))
                            .contains("spotless-plugin-gradle-" + version + ".jar"),
                    "project Spotless JAR used");

            Files.writeString(build, initial.replace("shortenFullyQualifiedTypes()", ""));
            String noShortening = formatter.format(file, SOURCE);
            check(noShortening.contains("java.util.List<String>"), "removed step automatically reflected");
            check(
                    formatter.importCount() == 2 && formatter.workerPid() != pid,
                    "changed configuration replaces worker");
            check(noShortening.equals(gradleOutput(root, file, SOURCE)), "removed-step Gradle parity");
            Files.writeString(props, "marker=SECOND\n");
            check(formatter.format(file, SOURCE).contains("SECOND"), "gradle.properties change");
            int imported = formatter.importCount();
            Files.writeString(script, "spotless { java { replace('marker', 'TOKEN', 'SCRIPT') } }\n");
            check(
                    formatter.format(file, SOURCE).contains("SCRIPT") && formatter.importCount() == imported + 1,
                    "applied Gradle script change");
            imported = formatter.importCount();
            Files.writeString(settings, pluginRepositories + "rootProject.name = 'formatter-renamed'\n");
            formatter.format(file, SOURCE);
            check(formatter.importCount() == imported + 1, "settings.gradle change");
            imported = formatter.importCount();
            Files.writeString(extra, "// changed external configuration\n");
            formatter.format(file, SOURCE);
            check(formatter.importCount() == imported + 1, "explicit external watch path");
            System.out.println(
                    "PASS: build.gradle, gradle.properties, applied script, settings.gradle, external file changes");

            check(formatter.format(excluded, SOURCE).equals(SOURCE), "Spotless targetExclude preserved");
            imported = formatter.importCount();
            formatter.format(excluded, SOURCE);
            check(formatter.importCount() == imported, "excluded target does not repeatedly import");
            Path added = file.resolveSibling("NewFile.java");
            Files.writeString(added, SOURCE);
            check(formatter.format(added, SOURCE).contains("SCRIPT"), "new target triggers membership refresh");
            // A path requested before its first save must not become a cached target exclusion.
            Path pending = file.resolveSibling("CreatedAfterRequest.java");
            check(!Files.exists(pending), "pending source does not exist yet");
            check(formatter.format(pending, SOURCE).equals(SOURCE), "missing source remains unchanged");
            imported = formatter.importCount();
            long beforeCreationPid = formatter.workerPid();
            Files.writeString(pending, SOURCE);
            String createdOutput = formatter.format(pending, SOURCE);
            check(
                    createdOutput.equals(gradleOutput(root, pending, SOURCE)),
                    "previously missing target matches Gradle after creation");
            check(formatter.importCount() == imported + 1, "created target refreshes membership once");
            check(formatter.workerPid() != beforeCreationPid, "created target replaces stale worker");
            imported = formatter.importCount();
            long createdPid = formatter.workerPid();
            check(formatter.format(pending, SOURCE).equals(createdOutput), "created target remains formatted");
            check(
                    formatter.importCount() == imported && formatter.workerPid() == createdPid,
                    "created target reuses refreshed worker");
            check(Files.readString(pending).equals(SOURCE), "new target source is not written by engine");
            System.out.println("PASS: missing path, later creation, Gradle parity and refreshed worker reuse");
            Files.writeString(build, "this is not valid Gradle!\n");
            try {
                formatter.format(file, SOURCE);
                throw new AssertionError("invalid Gradle config must fail closed");
            } catch (IOException expected) {
                check(expected.getMessage().contains("설정"), "configuration failure diagnostic");
            }
            Files.writeString(build, initial);
            check(formatter.format(file, SOURCE).contains("import java.util.List;"), "recovery after config repair");
            System.out.println(
                    "PASS: target excludes, new files, invalid configuration blocks old rules, repair recovery");

            Files.writeString(props, "palantirVersion=2.96.0\n");
            check(
                    formatter.format(file, SOURCE).equals(gradleOutput(root, file, SOURCE)),
                    "formatter version change and Gradle parity");
            System.out.println("PASS: Palantir 2.97.0 -> 2.96.0 resolved from project configuration");
            String google = initial.replace(
                    "palantirJavaFormat(providers.gradleProperty('palantirVersion').getOrElse('2.97.0'))",
                    "googleJavaFormat('" + googleVersion + "')");
            if (javaVersion == 17) {
                Files.writeString(build, google.replace("1.28.0", "1.30.0"));
                try {
                    formatter.format(file, SOURCE);
                    throw new AssertionError("JDK 17 must reject Google Java Format 1.30.0, not downgrade it");
                } catch (WorkerClient.SourceException expected) {
                    check(expected.getMessage().contains("1.30.0 requires JVM 21+"),
                            "formatter JDK requirement must reach the caller: " + expected.getMessage());
                }
                check(Files.readString(file).equals(SOURCE), "unsupported formatter preserves the source file");
                System.out.println("PASS: JDK 17 rejects Google Java Format 1.30.0 without a silent downgrade");
            }
            Files.writeString(build, google);
            String googleResult = formatter.format(file, SOURCE);
            checkGolden("google", googleResult);
            check(googleResult.contains("\n  List<String> names;"), "Google style selected from DSL");
            check(googleResult.equals(gradleOutput(root, file, SOURCE)), "Google Gradle parity");
            check(!googleResult.equals(formatted.replace("FIRST", "SCRIPT")), "formatter selection changes output");
            String eclipse = initial.replace(
                    "palantirJavaFormat(providers.gradleProperty('palantirVersion').getOrElse('2.97.0'))", "eclipse()");
            Files.writeString(build, eclipse);
            check(formatter.format(file, SOURCE).equals(gradleOutput(root, file, SOURCE)), "Eclipse Gradle parity");
            Files.writeString(build, initial);
            System.out.println(
                    "PASS: Palantir, Google and Eclipse selected by project DSL without an engine whitelist");

            // Native Spotless tasks must check without writes, and apply exactly the worker's rules.
            String expectedApplied = formatter.format(file, SOURCE);
            check(runGradle(root, "spotlessCheck") != 0, "check must reject unformatted source");
            check(Files.readString(file).equals(SOURCE), "check must not write source");
            check(runGradle(root, "spotlessApply") == 0, "apply task");
            check(Files.readString(file).equals(expectedApplied), "apply and worker parity");
            check(runGradle(root, "spotlessCheck") == 0, "check after apply");
            Files.writeString(file, SOURCE);
            System.out.println("PASS: spotlessApply / spotlessCheck native tasks and source preservation");

            Path sub = root.resolve("child");
            Files.createDirectories(sub.resolve("src/main/java"));
            Files.writeString(settings, pluginRepositories + "rootProject.name = 'formatter-test'\ninclude 'child'\n");
            Files.writeString(
                    sub.resolve("build.gradle"),
                    "plugins { id 'java'; id 'com.diffplug.spotless' }\nspotless { java { target 'src/**/*.java'; replace('child-marker', 'TOKEN', 'CHILD') } }\n");
            Path subFile = sub.resolve("src/main/java/Child.java");
            Files.writeString(subFile, SOURCE);
            String childOutput = formatter.format(subFile, SOURCE);
            check(
                    childOutput.contains("CHILD") && childOutput.contains("java.util.List<String>"),
                    "subproject selects its own evaluated rules");
            check(formatter.format(file, SOURCE).contains("SCRIPT"), "root still uses root rules");
            String childBuild = Files.readString(sub.resolve("build.gradle"));
            Files.writeString(sub.resolve("build.gradle"), childBuild.replace("; id 'com.diffplug.spotless'", ""));
            Files.writeString(build, initial + "\nsubprojects { apply plugin: 'com.diffplug.spotless' }\n");
            check(formatter.format(subFile, SOURCE).equals(childOutput), "Spotless applied by parent build");
            System.out.println("PASS: multi-project rules and indirect Spotless application");
            Files.writeString(build, initial + "\nspotless { encoding 'ISO-8859-1' }\n");
            try {
                formatter.format(file, SOURCE);
                throw new AssertionError("unencodable text must not be replaced with question marks");
            } catch (WorkerClient.SourceException expected) {
            }
            System.out.println("PASS: project encoding honored; unencodable text rejected without data loss");
            Files.writeString(settings, pluginRepositories + "rootProject.name = 'formatter-test'\n");
            Files.writeString(build, "plugins { id 'java' }\n");
            try {
                formatter.format(file, SOURCE);
                throw new AssertionError("project without Spotless must fail");
            } catch (IOException expected) {
                check(expected.getMessage().contains("설정"), "missing Spotless diagnostic");
            }
            Files.writeString(build, "plugins { id 'java'; id 'com.diffplug.spotless' version '" + version + "' }\n");
            try {
                formatter.format(file, SOURCE);
                throw new AssertionError("Spotless without Java configuration must fail");
            } catch (IOException expected) {
                check(expected.getMessage().contains("설정"), "missing Java configuration diagnostic");
            }
            Files.writeString(build, initial + "\nspotless { ratchetFrom 'HEAD' }\n");
            try {
                formatter.format(file, SOURCE);
                throw new AssertionError("ratchet configuration must fail");
            } catch (IOException expected) {
            }
            Files.writeString(build, initial);
            check(formatter.format(file, SOURCE).contains("import java.util.List;"), "repair after unsupported config");
            System.out.println("PASS: missing Spotless, unsupported configuration and repair");
            check(Files.readString(file).equals(SOURCE), "source remains untouched");

            // Spotless 8.0.0 predates shortenFullyQualifiedTypes; use its supported project rules.
            // Changing the project version must replace the worker and match that version's Gradle output.
            long previousPid = formatter.workerPid();
            int previousImports = formatter.importCount();
            Files.writeString(build, initial.replace(version, "8.0.0").replace("shortenFullyQualifiedTypes()", ""));
            String older = formatter.format(file, SOURCE);
            check(older.equals(gradleOutput(root, file, SOURCE)), "Spotless 8.0.0 Gradle parity");
            check(
                    formatter.workerPid() != previousPid && formatter.importCount() == previousImports + 1,
                    "Spotless version change replaces worker");
            try (var folders = Files.list(cache)) {
                check(
                        folders.anyMatch(folder -> {
                            try {
                                return Files.readString(folder.resolve("formatter-0/classpath.txt"))
                                        .contains("spotless-plugin-gradle-8.0.0.jar");
                            } catch (IOException ignored) {
                                return false;
                            }
                        }),
                        "older Spotless JAR exported from project");
            }
            Files.writeString(build, initial);
            check(
                    formatter.format(file, SOURCE).equals(gradleOutput(root, file, SOURCE)),
                    "restore original Spotless version");
            System.out.println(
                    "PASS: project Spotless 8.10.2 -> 8.0.0 -> 8.10.2, Gradle parity and worker replacement");
            kotlinDsl(temp, version, bridgeVersion, gradleVersion, javaHome, googleVersion);
            SharedDaemonSelfTest.run(root, file, SOURCE);
            System.out.println("ALL TESTS PASSED; Gradle=" + gradleVersion + "; JDK=" + javaVersion + "; fixture=" + temp);
        } finally {
            SharedDaemon.stop(root);
        }
    }

    private static void versionCompatibility(Path temp, Path engine, String bridgeVersion) throws Exception {
        Path oldGradle = temp.resolve("unsupported-gradle");
        installWrapper(oldGradle, "8.13");
        Files.writeString(oldGradle.resolve("settings.gradle"), "rootProject.name = 'unsupported-gradle'\n");
        Path engineJar;
        try (var jars = Files.list(engine.resolve("lib"))) {
            engineJar = jars.filter(path -> path.toString().endsWith(".jar")).findFirst().orElseThrow();
        }
        // Load the built JAR directly so Gradle's publication variant check cannot mask our diagnostic.
        Files.writeString(oldGradle.resolve("build.gradle"), """
                buildscript { dependencies { classpath files('%s') } }
                apply plugin: 'io.github.pozafly.spotlessbridge'
                """.formatted(engineJar.toAbsolutePath()));
        Path log = Files.createTempFile("spotless-bridge-version-", ".log");
        check(runGradle(oldGradle, log, "spotlessBridgeStart") != 0, "start must reject unsupported Gradle");
        String diagnostic = Files.readString(log);
        check(diagnostic.contains("Spotless Bridge does not support Gradle 8.13")
                        && diagnostic.contains("Supported Gradle range: >= 8.14.3 and < 10.0")
                        && diagnostic.contains("update Spotless Bridge"),
                "actionable unsupported Gradle diagnostic: " + diagnostic);
        check(!Files.exists(oldGradle.resolve(".gradle/spotless-bridge/endpoint.properties")),
                "unsupported Gradle must not start a daemon");

        Path root = temp.resolve("version-policy");
        installWrapper(root, "8.14.5");
        Files.writeString(root.resolve("settings.gradle"),
                pluginRepositories + "rootProject.name = 'version-policy'\ninclude 'child'\n");
        Files.writeString(root.resolve("build.gradle"),
                "plugins { id 'io.github.pozafly.spotlessbridge' version '" + bridgeVersion + "' }\n");
        Path child = root.resolve("child");
        Path file = child.resolve("src/main/java/Sample.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, SOURCE);
        Path build = child.resolve("build.gradle");
        String childBuild = """
                plugins { id 'java'; id 'com.diffplug.spotless' version '%s' }
                spotless { java { target 'src/**/*.java'; replace('marker', 'TOKEN', 'VERSION_POLICY') } }
                """;
        Path selected = root.resolve("files.txt");
        Files.writeString(selected, file.toString() + "\n");
        String apply = "-PspotlessBridgeFiles=" + selected;
        try {
            Files.writeString(build, childBuild.formatted("7.2.1"));
            check(runGradle(root, log, "spotlessBridgeStart") != 0, "start must reject unsupported Spotless");
            diagnostic = Files.readString(log);
            check(diagnostic.contains("Spotless Bridge does not support Spotless 7.2.1 in project :child")
                            && diagnostic.contains("Supported Spotless range: >= 8.0.0 and < 9.0.0")
                            && diagnostic.contains("spotlessApply task without Bridge"),
                    "actionable subproject Spotless diagnostic: " + diagnostic);
            check(Files.readString(file).equals(SOURCE), "rejected version must not modify sources");
            check(SharedDaemon.status(root) == -1, "rejected Spotless must not start a daemon");

            Files.writeString(build, childBuild.formatted("8.1.0"));
            check(runGradle(root, log, "spotlessBridgeApply", apply) == 0,
                    "unverified in-range versions must format: " + Files.readString(log));
            diagnostic = Files.readString(log);
            check(diagnostic.contains("Gradle 8.14.5 is within the supported range but has not been verified")
                            && diagnostic.contains("Spotless 8.1.0 in project :child is within the supported range but has not been verified"),
                    "unverified versions must warn: " + diagnostic);
            check(Files.readString(file).equals(SOURCE.replace("TOKEN", "VERSION_POLICY")),
                    "warning must not prevent project formatter execution");
            long pid = SharedDaemon.status(root);
            check(pid > 0, "in-range versions start a daemon");

            // A later downgrade must fail without applying the old daemon's cached rules.
            Files.writeString(file, SOURCE);
            Files.writeString(build, childBuild.formatted("7.2.1"));
            check(runGradle(root, log, "spotlessBridgeApply", apply) != 0, "unsupported downgrade must fail");
            check(Files.readString(file).equals(SOURCE), "unsupported downgrade must preserve source");
            check(SharedDaemon.status(root) == pid, "rejection must not replace an existing daemon");
            try (GradleFormatter formatter = new GradleFormatter(
                    root, Path.of(System.getProperty("java.home")), List.of(), () -> {})) {
                try {
                    formatter.format(file, SOURCE);
                    throw new AssertionError("client must reject unsupported Spotless");
                } catch (IOException expected) {
                    check(expected.getMessage().contains("Spotless Bridge does not support Spotless 7.2.1")
                                    && expected.getMessage().contains("Supported Spotless range"),
                            "IDE/hook client must expose version diagnostic: " + expected.getMessage());
                }
            }
            check(Files.readString(file).equals(SOURCE), "client rejection must preserve source");
        } finally {
            SharedDaemon.stop(root);
        }
        System.out.println("PASS: version range rejection, actionable diagnostics, in-range warnings and safe downgrade");
    }

    private static void kotlinDsl(
            Path temp, String version, String bridgeVersion, String gradleVersion, Path javaHome, String googleVersion)
            throws Exception {
        Path root = temp.resolve("kotlin-dsl");
        Path file = root.resolve("src/main/java/KotlinDsl.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, SOURCE);
        installWrapper(root, gradleVersion);
        Files.writeString(
                root.resolve("settings.gradle.kts"),
                pluginRepositories.replace("uri('", "uri(\"").replace("')", "\")")
                        + "rootProject.name = \"spotless-bridge-kotlin-test\"\n");
        Files.writeString(root.resolve("build.gradle.kts"), """
                plugins {
                    java
                    id("io.github.pozafly.spotlessbridge") version "%s"
                    id("com.diffplug.spotless") version "%s"
                }
                repositories { mavenCentral() }
                spotless {
                    java {
                        shortenFullyQualifiedTypes()
                        removeUnusedImports()
                        googleJavaFormat("%s")
                    }
                }
                """.formatted(bridgeVersion, version, googleVersion));
        try (GradleFormatter formatter = new GradleFormatter(root, javaHome, List.of(), () -> {})) {
            String result = formatter.format(file, SOURCE);
            check(
                    SharedDaemon.status(root) != SharedDaemon.status(temp.resolve("fixture")),
                    "different projects use different daemons");
            check(result.contains("import java.util.List;"), "Kotlin DSL shortening");
            check(result.contains("\n  List<String> names;"), "Kotlin DSL Google style");
            check(result.equals(gradleOutput(root, file, SOURCE)), "Kotlin DSL Gradle parity");
        }
        SharedDaemon.stop(root);
        System.out.println("PASS: build.gradle.kts native Spotless Kotlin DSL");
    }

    private static void installWrapper(Path root, String version) throws Exception {
        check(version.matches("[0-9]+\\.[0-9]+(?:\\.[0-9]+)?"), "Use an explicit stable Gradle version");
        Path wrapper = root.resolve("gradle/wrapper");
        Files.createDirectories(wrapper);
        Files.copy(Path.of("gradle/wrapper/gradle-wrapper.jar"), wrapper.resolve("gradle-wrapper.jar"));
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(Path.of("gradle/wrapper/gradle-wrapper.properties"))) {
            properties.load(input);
        }
        properties.setProperty("distributionUrl", "https://services.gradle.org/distributions/gradle-" + version + "-bin.zip");
        // The source wrapper may pin a checksum for a different distribution.
        properties.remove("distributionSha256Sum");
        try (OutputStream output = Files.newOutputStream(wrapper.resolve("gradle-wrapper.properties"))) {
            properties.store(output, "Compatibility test consumer wrapper");
        }
        Path log = Files.createTempFile("spotless-bridge-gradle-version-", ".log");
        Process process = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-classpath", wrapper.resolve("gradle-wrapper.jar").toString(),
                        "org.gradle.wrapper.GradleWrapperMain", "--version", "--console=plain")
                .directory(root.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        if (!process.waitFor(180, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("Gradle distribution preparation timed out: " + log);
        }
        String output = Files.readString(log);
        check(process.exitValue() == 0 && output.lines().anyMatch(line -> line.equals("Gradle " + version)),
                "Consumer Gradle version mismatch: " + log + "\n" + output);
        System.out.println("PASS: consumer Gradle " + version + " at " + root);
    }

    private static int runGradle(Path root, String task) throws Exception {
        return runGradle(root, Files.createTempFile("spotless-bridge-task-", ".log"), task);
    }

    private static int runGradle(Path root, Path log, String... tasks) throws Exception {
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-classpath", root.resolve("gradle/wrapper/gradle-wrapper.jar").toString(),
                "org.gradle.wrapper.GradleWrapperMain", "--console=plain", "--no-configuration-cache"));
        command.addAll(List.of(tasks));
        Process process = new ProcessBuilder(command)
                .directory(root.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("Gradle task timeout: " + log);
        }
        System.out.println("Task " + String.join(" ", tasks) + ": exit=" + process.exitValue() + " log=" + log);
        return process.exitValue();
    }

    private static String gradleOutput(Path root, Path file, String input) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Path out = Files.createTempFile("spotless-reference-", ".out");
        Path err = Files.createTempFile("spotless-reference-", ".err");
        Process process = new ProcessBuilder(
                        java.toString(),
                        "-classpath",
                        root.resolve("gradle/wrapper/gradle-wrapper.jar").toString(),
                        "org.gradle.wrapper.GradleWrapperMain",
                        "--quiet",
                        "--no-configuration-cache",
                        "spotlessApply",
                        "-PspotlessIdeHook=" + file.toRealPath(),
                        "-PspotlessIdeHookUseStdIn",
                        "-PspotlessIdeHookUseStdOut")
                .directory(root.toFile())
                .redirectOutput(out.toFile())
                .redirectError(err.toFile())
                .start();
        try (OutputStream stream = process.getOutputStream()) {
            stream.write(input.getBytes(StandardCharsets.UTF_8));
        }
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("Gradle reference timed out");
        }
        check(process.exitValue() == 0, Files.readString(err));
        check(
                Files.readString(err).contains("IS DIRTY")
                        || Files.readString(err).contains("IS CLEAN"),
                "Gradle reference did not process the target");
        return Files.readString(err).contains("IS DIRTY") ? Files.readString(out) : input;
    }

    private static void checkGolden(String name, String output) throws IOException {
        try (InputStream input = FormatterSelfTest.class.getResourceAsStream("/" + name + ".expected")) {
            check(input != null, "missing expected output: " + name);
            check(
                    output.equals(new String(input.readAllBytes(), StandardCharsets.UTF_8)),
                    name + " output changed; review the dependency update before changing the expected output");
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
