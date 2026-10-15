package dev.local.javaformatter;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Real isolated Gradle builds; never changes an application repository or IDE settings. */
public final class FormatterSelfTest {
    private static final String SOURCE =
            "import java.util.Set;\n// TOKEN\nclass Sample{java.util.List<String> names;String label=\"안녕하세요 😀\";}\n";

    public static void main(String[] args) throws Exception {
        Path engine = Path.of(args[0]);
        Path temp = Files.createTempDirectory("dynamic-spotless-test-");
        Path root = temp.resolve("fixture");
        Path cache = temp.resolve("cache");
        Path file = root.resolve("src/main/java/Sample.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, SOURCE);
        Path excluded = file.resolveSibling("Excluded.java");
        Files.writeString(excluded, SOURCE);
        Path wrapper = root.resolve("gradle/wrapper");
        Files.createDirectories(wrapper);
        for (String name : List.of("gradle-wrapper.jar", "gradle-wrapper.properties"))
            Files.copy(Path.of("gradle/wrapper", name), wrapper.resolve(name));
        Path settings = root.resolve("settings.gradle");
        Files.writeString(settings, "rootProject.name = 'formatter-test'\n");
        Path build = root.resolve("build.gradle");
        String initial = """
                plugins { id 'java'; id 'com.diffplug.spotless' version '8.10.2' }
                repositories { mavenCentral() }
                spotless { java {
                    target 'src/**/*.java'
                    targetExclude '**/Excluded.java'
                    shortenFullyQualifiedTypes()
                    removeUnusedImports()
                    palantirJavaFormat(providers.gradleProperty('palantirVersion').getOrElse('2.97.0'))
                } }
                apply from: 'format.gradle'
                """;
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
                engine,
                cache,
                List.of(extra),
                () -> System.out.println("Importing fixture Spotless configuration..."))) {
            String formatted = formatter.format(file, SOURCE);
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
            Files.writeString(settings, "rootProject.name = 'formatter-renamed'\n");
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
            check(formatter.importCount() == imported && formatter.workerPid() == createdPid,
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
            Path sub = root.resolve("child");
            Files.createDirectories(sub.resolve("src/main/java"));
            Files.writeString(settings, "rootProject.name = 'formatter-test'\ninclude 'child'\n");
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
            System.out.println("PASS: multi-project rule routing");
            Files.writeString(build, initial + "\nspotless { encoding 'ISO-8859-1' }\n");
            try {
                formatter.format(file, SOURCE);
                throw new AssertionError("unencodable text must not be replaced with question marks");
            } catch (WorkerClient.SourceException expected) {
            }
            System.out.println("PASS: project encoding honored; unencodable text rejected without data loss");
            check(Files.readString(file).equals(SOURCE), "source remains untouched");
            System.out.println("ALL TESTS PASSED; fixture=" + temp);
        }
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

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
