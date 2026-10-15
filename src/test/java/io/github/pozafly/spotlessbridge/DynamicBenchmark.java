package io.github.pozafly.spotlessbridge;

import java.nio.file.*;
import java.util.*;

/** Optional full request-path measurement against copies and previously captured Gradle outputs. */
public final class DynamicBenchmark {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        try (GradleFormatter formatter =
                new GradleFormatter(root, Path.of(System.getProperty("java.home")), List.of(), () -> {})) {
            long first = System.nanoTime();
            List<List<Double>> timings = List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
            long firstMs = 0;
            for (int round = 0; round < 14; round++) {
                for (int i = 0; i < 3; i++) {
                    Path file = root.resolve("src/main/java/Sample" + i + ".java");
                    String input = Files.readString(file);
                    long start = System.nanoTime();
                    String output = formatter.format(file, input);
                    timings.get(i).add((System.nanoTime() - start) / 1_000_000.0);
                    if (round == 0 && i == 0) firstMs = (System.nanoTime() - first) / 1_000_000;
                    if (!output.equals(Files.readString(root.resolve("expected" + i + ".txt"))))
                        throw new AssertionError("Gradle parity: " + i);
                }
            }
            System.out.println("first_request_with_gradle_ms=" + firstMs);
            System.out.println("gradle_imports=" + formatter.importCount());
            for (int i = 0; i < 3; i++) {
                List<Double> measured = new ArrayList<>(timings.get(i).subList(4, 14));
                Collections.sort(measured);
                System.out.println("sample=" + i + " median_ms=" + (measured.get(4) + measured.get(5)) / 2 + " min_ms="
                        + measured.getFirst() + " max_ms=" + measured.getLast());
            }
            System.out.println(
                    "PASS: 42 results match reference Gradle outputs; full request path including file change detection");
        } finally {
            SharedDaemon.stop(root);
        }
    }
}
