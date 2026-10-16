package io.github.pozafly.spotlessbridge;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Runs the packaged executable and both launcher paths against a real isolated consumer on every native OS. */
public final class NativeCliSelfTest {
    private static final String SOURCE = "class Example{String text=\"한글 😀\";}\n";

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("bridge-native-test-").resolve("project space 한글");
        Path file = root.resolve("src/main/java/Example.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, SOURCE);
        Path wrapper = root.resolve("gradle/wrapper");
        Files.createDirectories(wrapper);
        Files.copy(Path.of("gradle/wrapper/gradle-wrapper.jar"), wrapper.resolve("gradle-wrapper.jar"));
        Files.copy(Path.of("gradle/wrapper/gradle-wrapper.properties"), wrapper.resolve("gradle-wrapper.properties"));
        Files.writeString(root.resolve("settings.gradle"), "pluginManagement { repositories { maven { url = uri('"
                + Path.of(args[0]).toUri() + "') }; gradlePluginPortal() } }\nrootProject.name = 'native-client-test'\n");
        String build = """
                plugins { id 'java'; id 'com.diffplug.spotless' version '8.10.2'; id 'io.github.pozafly.spotlessbridge' version '%s' }
                repositories { mavenCentral() }
                spotless { java { target 'src/**/*.java'; targetExclude '**/Excluded.java'; googleJavaFormat('1.30.0') } }
                new File(rootDir, 'gradle-calls.txt').append('called\\n')
                """.formatted(args[1]);
        Files.writeString(root.resolve("build.gradle"), build);
        Path state = root.resolve(".gradle/spotless-bridge");
        try {
            gradle(root, "help");
            Path binary = state.resolve(Files.readString(state.resolve("native-client.txt")).strip());
            check(run(root, 0, List.of(binary.toString(), "--version")).contains(args[1]), "native version matches plugin");
            nativeApply(root, file); // No IDE, endpoint or prepared engine: CLI bootstraps Gradle.
            String expected = Files.readString(file);
            check(!expected.equals(SOURCE), "cold hook formats");
            Files.writeString(file, SOURCE);
            gradle(root, "spotlessApply");
            check(Files.readString(file).equals(expected), "Spotless byte parity");
            long pid = SharedDaemon.status(root), worker = SharedDaemon.workerPid(root);
            long calls = calls(root);
            Files.writeString(file, SOURCE);
            nativeApply(root, file);
            launcher(root, file);
            check(calls(root) == calls, "native CLI and launcher skip Gradle when warm");
            check(SharedDaemon.status(root) == pid && SharedDaemon.workerPid(root) == worker, "warm PID reuse");

            Path added = file.resolveSibling("New, 한글 😀.java");
            Files.writeString(added, SOURCE);
            nativeApply(root, added);
            check(calls(root) == calls + 1 && Files.readString(added).equals(expected), "new Unicode path refreshes once");
            calls = calls(root);
            Files.writeString(root.resolve("build.gradle"), build + "\nspotless { java { replace('text', '한글', '변경') } }\n");
            Files.writeString(file, SOURCE);
            nativeApply(root, file);
            check(calls(root) == calls + 1 && Files.readString(file).contains("변경"), "configuration refresh");

            Path excluded = file.resolveSibling("Excluded.java");
            Files.writeString(excluded, SOURCE);
            nativeApply(root, excluded);
            check(Files.readString(excluded).equals(SOURCE), "excluded file unchanged");
            Files.writeString(file, SOURCE);
            Path missing = file.resolveSibling("Missing.java");
            run(root, 1, nativeCommand(root, file, missing));
            check(Files.readString(file).equals(SOURCE), "missing file prevents partial writes");
            Path invalid = file.resolveSibling("Invalid.java");
            Files.writeString(invalid, "class { not java");
            run(root, 1, nativeCommand(root, file, invalid));
            check(Files.readString(file).equals(SOURCE), "format error prevents partial writes");
            Files.delete(invalid);
            nativeApply(root, file);

            ExecutorService pool = Executors.newFixedThreadPool(4);
            try {
                List<Future<?>> requests = new ArrayList<>();
                for (int i = 0; i < 12; i++) requests.add(pool.submit(() -> { nativeApply(root, file); return null; }));
                for (Future<?> request : requests) request.get(60, TimeUnit.SECONDS);
            } finally { pool.shutdownNow(); }
            check(SharedDaemon.status(root) == pid, "concurrent native clients reuse daemon");
            // A lost/unauthenticated response is not a safe reason to repeat a possibly completed apply.
            Path endpointPath = state.resolve("endpoint.properties");
            byte[] savedEndpoint = Files.readAllBytes(endpointPath);
            Properties badEndpoint = SharedDaemon.read(endpointPath);
            badEndpoint.setProperty("token", "incorrect-token");
            try (OutputStream out = Files.newOutputStream(endpointPath)) { badEndpoint.store(out, "test"); }
            calls = calls(root);
            try {
                String error = run(root, 1, nativeCommand(root, file));
                check(error.contains("no automatic retry"), "ambiguous response is not retried");
                check(calls(root) == calls, "communication failure does not bootstrap Gradle");
            } finally { Files.write(endpointPath, savedEndpoint); }
            Files.delete(Path.of(SharedDaemon.endpoint(root).getProperty("snapshot"), "snapshot.properties"));
            nativeApply(root, file);
            check(calls(root) == calls + 1, "missing snapshot recovery");
            String configured = Files.readString(root.resolve("build.gradle"));
            Files.writeString(root.resolve("build.gradle"), configured +
                    "\ntasks.named('spotlessBridgeStart') { doFirst { file('build.gradle').append('\\n// changed during evaluation\\n') } }\n");
            Files.writeString(file, SOURCE);
            run(root, 1, nativeCommand(root, file));
            check(Files.readString(file).equals(SOURCE), "configuration changes during export block writes");
            Files.writeString(root.resolve("build.gradle"), configured);
            SharedDaemon.stop(root);
            nativeApply(root, file);
            check(SharedDaemon.status(root) > 0 && SharedDaemon.status(root) != pid, "headless restart");
            SharedDaemon.stop(root); // Windows does not allow deleting the JAR of a running JVM.
            Files.delete(Path.of(Files.readAllLines(state.resolve("client-runtime.txt")).get(0)));
            nativeApply(root, file);
            check(Files.isRegularFile(Path.of(Files.readAllLines(state.resolve("client-runtime.txt")).get(0))), "missing engine recovery");
            long beforeHomeChange = SharedDaemon.status(root);
            String gradleHome = System.getenv("GRADLE_USER_HOME");
            if (gradleHome == null) gradleHome = Path.of(System.getProperty("user.home"), ".gradle").toString();
            run(root, 0, nativeCommand(root, file), Path.of(gradleHome).resolve(".").toString());
            check(SharedDaemon.status(root) != beforeHomeChange, "changed Gradle environment restarts daemon");
            nativeApply(root, file);
            Files.delete(binary);
            Files.writeString(file, SOURCE);
            launcher(root, file);
            check(Files.readString(file).contains("변경"), "missing native binary falls back to Java safely");
            gradle(root, "help");
            check(Files.isRegularFile(binary), "Gradle restores missing native binary");
            nativeApply(root, file);
            System.out.println("PASS: native package, cold/warm, launcher, parity, Unicode, config/new/excluded files, failure safety, concurrency, recovery");
        } catch (Exception | AssertionError failure) {
            try (var logs = Files.walk(state)) {
                for (Path log : logs.filter(p -> p.toString().endsWith(".log")).toList()) {
                    System.err.println("Diagnostic log: " + log);
                    System.err.println(Files.readString(log));
                }
            } catch (IOException diagnosticFailure) {
                failure.addSuppressed(diagnosticFailure);
            }
            throw failure;
        } finally {
            SharedDaemon.stop(root);
        }
    }

    private static long calls(Path root) throws IOException {
        return Files.readAllLines(root.resolve("gradle-calls.txt")).size();
    }

    private static List<String> nativeCommand(Path root, Path... files) throws IOException {
        String binary = root.resolve(".gradle/spotless-bridge").resolve(
                Files.readString(root.resolve(".gradle/spotless-bridge/native-client.txt")).strip()).toString();
        List<String> command = new ArrayList<>(List.of(binary, "--root", root.toString(), "apply", "--"));
        for (Path file : files) command.add(file.toString());
        return command;
    }

    private static void nativeApply(Path root, Path... files) throws Exception {
        run(root, 0, nativeCommand(root, files));
    }

    private static void launcher(Path root, Path file) throws Exception {
        List<String> command = new ArrayList<>();
        boolean windows = System.getProperty("os.name").startsWith("Windows");
        if (windows) command.addAll(List.of("cmd.exe", "/d", "/c"));
        command.add(root.resolve(".gradle/spotless-bridge/spotless-bridge" + (windows ? ".cmd" : "")).toString());
        command.add(file.toString());
        run(root, 0, command);
    }

    private static void gradle(Path root, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString(),
                "-classpath", "gradle/wrapper/gradle-wrapper.jar",
                "org.gradle.wrapper.GradleWrapperMain", "--console=plain", "--no-configuration-cache"));
        command.addAll(List.of(args));
        run(root, 0, command);
    }

    private static String run(Path root, int expected, List<String> command) throws Exception {
        return run(root, expected, command, null);
    }

    private static String run(Path root, int expected, List<String> command, String gradleHome) throws Exception {
        Path log = Files.createTempFile("bridge-native-check-", ".log");
        ProcessBuilder builder = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("JAVA_HOME", System.getProperty("java.home"));
        if (gradleHome != null) builder.environment().put("GRADLE_USER_HOME", gradleHome);
        Process process = builder.start();
        if (!process.waitFor(180, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("Command timeout; log: " + log);
        }
        String output = Files.readString(log, StandardCharsets.UTF_8);
        check(process.exitValue() == expected, "exit=" + process.exitValue() + ", expected=" + expected
                + "\ncommand=" + command + "\n" + output);
        return output;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
