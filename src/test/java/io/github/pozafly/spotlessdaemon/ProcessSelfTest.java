package io.github.pozafly.spotlessdaemon;

import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.*;

/** Process-level checks; does not start or change the user's IDE. */
public final class ProcessSelfTest {
    private static final String SOURCE =
            "import java.util.Set;\nclass Sample{java.util.List<String> names;String label=\"안녕하세요 😀\";}\n";

    public static void main(String[] args) throws Exception {
        Path engine = Path.of(args[0]);
        Path snapshot = Path.of(args[1]);
        Path file = Path.of(args[2]);
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Path log = Files.createTempFile("spotless-daemon-test-", ".log");
        long child;
        try (WorkerClient client = new WorkerClient(java, engine, log, snapshot, Duration.ofSeconds(15))) {
            String formatted = client.format(file, SOURCE);
            child = client.pid();
            check(formatted.contains("import java.util.List;"), "FQN shortening");
            check(!formatted.contains("import java.util.Set;"), "unused imports");
            check(formatted.contains("    List<String> names;"), "Palantir indentation");
            check(formatted.contains("안녕하세요 😀"), "UTF-8 roundtrip");
            check(client.format(file, formatted).equals(formatted), "idempotence");
            check(client.pid() == child, "process reuse");
            try {
                client.format(file, "class Broken { void method( ");
                throw new AssertionError("invalid source accepted");
            } catch (WorkerClient.SourceException expected) {
            }
            check(client.format(file, SOURCE).equals(formatted), "recovery after parser error");
            check(client.pid() == child, "parser error should preserve worker");
            ExecutorService callers = Executors.newFixedThreadPool(3);
            try {
                Future<String> first = callers.submit(() -> client.format(file, SOURCE));
                Future<String> second = callers.submit(() -> client.format(file, SOURCE.replace("Sample", "Other")));
                check(first.get().equals(formatted), "concurrent request 1");
                check(second.get().contains("class Other"), "concurrent request 2");
            } finally {
                callers.shutdownNow();
            }
            ProcessHandle.of(child).orElseThrow().destroyForcibly();
            ProcessHandle.of(child).ifPresent(p -> p.onExit().join());
            check(client.format(file, SOURCE).equals(formatted), "restart after process death");
            check(client.pid() != child, "restarted process identity");
            child = client.pid();
        }
        long closedPid = child;
        ProcessHandle.of(closedPid)
                .ifPresent(p -> p.onExit().orTimeout(5, TimeUnit.SECONDS).join());
        check(ProcessHandle.of(closedPid).isEmpty(), "close terminates worker");
        try (WorkerClient shortDeadline = new WorkerClient(java, engine, log, snapshot, Duration.ofMillis(1))) {
            try {
                shortDeadline.format(file, SOURCE);
                throw new AssertionError("timeout expected");
            } catch (IOException expected) {
                check(expected.getMessage().contains("timed out"), "timeout diagnostic");
            }
            check(shortDeadline.pid() == -1, "timeout clears worker");
        }
        // Ensure disposal can interrupt an active request rather than waiting for its lock.
        WorkerClient closing = new WorkerClient(java, engine, log, snapshot, Duration.ofSeconds(15));
        CompletableFuture<Void> request = CompletableFuture.runAsync(() -> {
            try {
                closing.format(file, SOURCE);
            } catch (IOException expected) {
            }
        });
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (closing.pid() == -1 && !request.isDone() && System.nanoTime() < until) Thread.onSpinWait();
        closing.close();
        request.get(5, TimeUnit.SECONDS);
        System.out.println(
                "PASS: shortening, unused imports, Palantir, UTF-8, idempotence, reuse, invalid-source recovery, concurrency, restart, timeout, shutdown");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
