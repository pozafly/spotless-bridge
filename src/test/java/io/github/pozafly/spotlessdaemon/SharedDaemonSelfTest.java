package io.github.pozafly.spotlessdaemon;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Editor buffers and an AI hook use the actual published plugin in an isolated consumer build. */
public final class SharedDaemonSelfTest {
    static void run(Path root, Path file, String source) throws Exception {
        Path javaHome = Path.of(System.getProperty("java.home"));
        // A new CLI initially knows only the root; a cached snapshot also watches this external included build.
        Path included = root.getParent().resolve("included-build");
        Files.createDirectories(included);
        Files.writeString(included.resolve("settings.gradle"), "rootProject.name = 'external-build'\n");
        Files.writeString(included.resolve("build.gradle"), "// external configuration\n");
        Path settings = root.resolve("settings.gradle");
        Files.writeString(settings, Files.readString(settings) + "\nincludeBuild '../included-build'\n");
        gradle(root, "spotlessDaemonStart");
        String expected;
        long pid = SharedDaemon.status(root);
        try (GradleFormatter editor = new GradleFormatter(root, javaHome, List.of(), () -> {})) {
            expected = editor.format(file, source);
            check(SharedDaemon.status(root) == pid, "editor attaches to existing project daemon");
        }
        check(SharedDaemon.status(root) == pid, "editor close preserves project daemon");
        Properties endpoint = SharedDaemon.endpoint(root);
        long worker = SharedDaemon.workerPid(root);
        ExecutorService pool = Executors.newFixedThreadPool(20);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < 80; i++) results.add(pool.submit(() -> SharedDaemon.format(endpoint, file, source)));
            for (Future<String> result : results)
                check(expected.equals(result.get(60, TimeUnit.SECONDS)), "concurrent exact output");
        } finally {
            pool.shutdownNow();
        }
        check(SharedDaemon.workerPid(root) == worker, "concurrent requests reuse one worker");
        Path list = root.resolve("hook-files.txt");
        Files.writeString(list, file + "\n");
        Files.writeString(file, source);
        // Installing the probe changes tracked Gradle configuration; refresh once before measuring the warm hook.
        Path counter = root.resolve("gradle-calls.txt");
        Path buildScript = root.resolve("build.gradle");
        Files.writeString(
                buildScript,
                Files.readString(buildScript) + "\nnew File(rootDir, 'gradle-calls.txt').append('called\\n')\n");
        gradle(root, "spotlessDaemonStart");
        worker = SharedDaemon.workerPid(root);
        // Refresh replaced the worker; materialize it before checking reuse.
        SharedDaemon.format(SharedDaemon.endpoint(root), file, source);
        worker = SharedDaemon.workerPid(root);
        long calls = Files.readAllLines(counter).size();
        hook(root, file);
        hook(root, file);
        check(Files.readAllLines(counter).size() == calls, "warm external hooks execute zero Gradle builds");
        check(Files.readString(file).equals(expected), "hook apply matches editor buffer");
        check(SharedDaemon.status(root) == pid, "hook and editor share daemon PID");
        check(SharedDaemon.workerPid(root) == worker, "unchanged hook export reuses formatter");
        check(
                Files.readString(file.resolveSibling("Excluded.java")).equals(source),
                "hook leaves unselected excluded file unchanged");
        System.out.println(
                "PASS: editor close, headless hook apply, shared PID and worker, 80 concurrent exact outputs");

        // Stop all editor clients, then let only the Gradle hook start the daemon from scratch.
        SharedDaemon.stop(root);
        check(SharedDaemon.status(root) == -1, "explicit stop removes endpoint");
        Files.writeString(file, source);
        hook(root, file);
        check(Files.readAllLines(counter).size() == calls + 1, "cold hook invokes Gradle exactly once");
        check(SharedDaemon.status(root) != pid && SharedDaemon.status(root) > 0, "headless hook cold start");
        check(Files.readString(file).equals(expected), "headless cold-start output");
        pid = SharedDaemon.status(root);
        // Real process death leaves a stale endpoint; an editor must bootstrap through Gradle again.
        long orphan = SharedDaemon.workerPid(root);
        ProcessHandle killed = ProcessHandle.of(pid).orElseThrow();
        killed.destroyForcibly();
        killed.onExit().get(5, TimeUnit.SECONDS);
        ProcessHandle.of(orphan)
                .ifPresent(
                        child -> child.onExit().orTimeout(5, TimeUnit.SECONDS).join());
        check(ProcessHandle.of(orphan).isEmpty(), "daemon death also stops its worker");
        try (GradleFormatter editor = new GradleFormatter(root, javaHome, List.of(), () -> {})) {
            check(editor.format(file, source).equals(expected), "stale endpoint recovery");
            check(SharedDaemon.status(root) != pid, "dead daemon replaced");
        }
        calls = Files.readAllLines(counter).size();
        Files.writeString(buildScript, Files.readString(buildScript) + "\n// hook configuration refresh\n");
        Files.writeString(file, source);
        hook(root, file);
        check(Files.readAllLines(counter).size() == calls + 1, "changed config invokes Gradle exactly once");
        hook(root, file);
        check(Files.readAllLines(counter).size() == calls + 1, "refreshed hook returns to zero Gradle calls");
        check(Files.readString(file).equals(expected), "hook config-refresh output");
        Path added = file.resolveSibling("New, with spaces.java");
        Files.writeString(added, source);
        hook(root, added);
        check(Files.readAllLines(counter).size() == calls + 2, "new hook file refreshes targets once");
        check(Files.readString(added).equals(expected), "new hook file preserves spaces and commas");
        hook(root, added);
        check(Files.readAllLines(counter).size() == calls + 2, "known hook file skips Gradle");
        Files.delete(added);
        System.out.println("PASS: zero-Gradle warm hooks, one-Gradle cold/config refresh");
        System.out.println("PASS: hook-only cold start, daemon death, stale endpoint recovery");

        // Authentication fails before any format operation and the next valid client still succeeds.
        Properties bad = new Properties();
        bad.putAll(SharedDaemon.endpoint(root));
        bad.setProperty("token", "invalid");
        try {
            SharedDaemon.format(bad, file, source);
            throw new AssertionError("invalid token accepted");
        } catch (IOException expectedFailure) {
        }
        check(
                SharedDaemon.format(SharedDaemon.endpoint(root), file, source).equals(expected),
                "valid client after rejected token");

        // Real disk output honors the project's encoding and CRLF, while editor normalization stays in the adapter.
        Path build = root.resolve("build.gradle");
        String previous = Files.readString(build);
        try {
            Files.writeString(build, previous + "\nspotless { encoding 'UTF-16LE'; lineEndings 'WINDOWS' }\n");
            Files.write(file, source.getBytes(java.nio.charset.StandardCharsets.UTF_16LE));
            gradle(root, "spotlessDaemonApply", "-PspotlessDaemonFiles=" + list);
            byte[] actual = Files.readAllBytes(file);
            String text = new String(actual, java.nio.charset.StandardCharsets.UTF_16LE);
            check(text.contains("\r\n") && !text.replace("\r\n", "").contains("\n"), "disk CRLF preserved");
            // Ordinary Spotless must produce byte-identical output for the same selected file.
            Files.write(file, source.getBytes(java.nio.charset.StandardCharsets.UTF_16LE));
            gradle(root, "spotlessApply", "-PspotlessIdeHook=" + file);
            check(Arrays.equals(actual, Files.readAllBytes(file)), "UTF-16LE CRLF ordinary Gradle byte parity");
        } finally {
            Files.writeString(build, previous);
            Files.writeString(file, source);
        }
        SharedDaemon.stop(root);
        // Two independent Gradle callers start simultaneously. The lifetime lock allows one server only.
        ExecutorService starters = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = starters.submit(() -> {
                gradle(root, "spotlessDaemonStart");
                return null;
            });
            Future<?> second = starters.submit(() -> {
                gradle(root, "spotlessDaemonStart");
                return null;
            });
            first.get(120, TimeUnit.SECONDS);
            second.get(120, TimeUnit.SECONDS);
        } finally {
            starters.shutdownNow();
        }
        check(SharedDaemon.status(root) > 0, "concurrent startup ready");
        SharedDaemon.stop(root);
        gradle(root, "spotlessDaemonStart", "-DspotlessDaemon.idleSeconds=2");
        long idlePid = SharedDaemon.status(root);
        ProcessHandle.of(idlePid).orElseThrow().onExit().get(10, TimeUnit.SECONDS);
        check(SharedDaemon.status(root) == -1, "idle exit removes endpoint");
        System.out.println("PASS: authentication, encoding/CRLF parity, concurrent startup, explicit stop, idle exit");
    }

    private static void hook(Path root, Path file) throws Exception {
        // Use the shipped hook adapter as an external process, with no editor client alive.
        Path log = Files.createTempFile("shared-daemon-hook-", ".log");
        Process process = new ProcessBuilder(
                        "python3",
                        Path.of("scripts/ai-hook.py").toAbsolutePath().toString(),
                        root.toString(),
                        file.toString())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        if (!process.waitFor(180, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("Hook timeout: " + log);
        }
        check(process.exitValue() == 0, "Hook failed: " + Files.readString(log));
    }

    private static void gradle(Path root, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                root.resolve("gradle/wrapper/gradle-wrapper.jar").toString(),
                "org.gradle.wrapper.GradleWrapperMain",
                "--console=plain",
                "--no-configuration-cache"));
        command.addAll(List.of(args));
        Path log = Files.createTempFile("shared-daemon-gradle-", ".log");
        Process process = new ProcessBuilder(command)
                .directory(root.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("Gradle timeout: " + log);
        }
        check(process.exitValue() == 0, "Gradle failed: " + log + "\n" + Files.readString(log));
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
