package dev.local.javaformatter;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Evaluates Gradle only on the first request, changed configuration, or a newly encountered target. */
public final class GradleFormatter implements AutoCloseable {
    private static final Set<String> IGNORED =
            Set.of(".git", ".idea", ".gradle", "build", "out", "node_modules", ".venv");
    private final Path root, javaHome, engineDirectory, cacheDirectory;
    private final List<Path> extraWatch;
    private final Runnable importing;
    private final AtomicReference<Process> exportProcess = new AtomicReference<>();
    private volatile WorkerClient worker;
    private volatile boolean closed;
    private String fingerprint;
    private List<Path> watchedRoots;
    private Properties metadata;
    private final Set<Path> excluded = new HashSet<>();
    private int imports;

    public GradleFormatter(
            Path root,
            Path javaHome,
            Path engineDirectory,
            Path cacheDirectory,
            List<Path> extraWatch,
            Runnable importing)
            throws IOException {
        this.root = root.toRealPath();
        this.javaHome = javaHome;
        this.engineDirectory = engineDirectory;
        this.cacheDirectory = cacheDirectory;
        this.extraWatch = List.copyOf(extraWatch);
        this.importing = importing;
        watchedRoots = List.of(this.root);
        Files.createDirectories(cacheDirectory);
    }

    public synchronized String format(Path file, String source) throws IOException {
        if (closed) throw new IOException("Formatter is stopped");
        Path canonical = file.toFile().getCanonicalFile().toPath();
        String current = fingerprint();
        boolean unknown =
                metadata == null || (!metadata.containsKey("target." + canonical) && !excluded.contains(canonical));
        if (worker == null || !current.equals(fingerprint) || unknown) reload(current);
        if (!metadata.containsKey("target." + canonical)) {
            // Missing paths can become targets on their first save; do not cache them as exclusions.
            if (Files.isRegularFile(canonical)) excluded.add(canonical);
            return source;
        }
        WorkerClient selected = worker;
        String formatted = selected.format(canonical, source);
        // A build script may be saved while this request is running. Never return the old rules' result.
        if (closed || selected != worker || !fingerprint.equals(fingerprint())) {
            throw new IOException("Spotless 설정이 포맷 도중 변경되었습니다. 다시 저장하면 새 설정을 적용합니다.");
        }
        return formatted;
    }

    private void reload(String before) throws IOException {
        WorkerClient previous = worker;
        worker = null;
        fingerprint = null;
        metadata = null;
        excluded.clear();
        if (previous != null) previous.close();
        importing.run();
        Path output = Files.createTempDirectory(cacheDirectory, "snapshot-");
        Path log = output.resolve("gradle-export.log");
        Path java = javaHome.resolve("bin").resolve(isWindows() ? "java.exe" : "java");
        Path wrapper = root.resolve("gradle/wrapper/gradle-wrapper.jar");
        if (!Files.isRegularFile(wrapper)) throw new IOException("Gradle wrapper JAR를 찾을 수 없습니다: " + wrapper);
        ProcessBuilder builder = new ProcessBuilder(
                        java.toString(),
                        "-classpath",
                        wrapper.toString(),
                        "org.gradle.wrapper.GradleWrapperMain",
                        "--no-configuration-cache",
                        "--console=plain",
                        "--quiet",
                        "-I",
                        engineDirectory.resolve("export.gradle").toString(),
                        "-DjavaSaveFormatter.output=" + output,
                        ":__javaSaveFormatterExport")
                .directory(root.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile());
        builder.environment().put("JAVA_HOME", javaHome.toString());
        Process process = builder.start();
        exportProcess.set(process);
        if (closed) {
            process.destroyForcibly();
            throw new IOException("Formatter is stopped");
        }
        try {
            if (!process.waitFor(120, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("Spotless 설정을 120초 안에 가져오지 못했습니다. 로그: " + log);
            }
            if (process.exitValue() != 0)
                throw new IOException("Spotless 설정을 가져오지 못했습니다. 이전 규칙은 적용하지 않습니다. 로그: " + log);
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Spotless 설정 불러오기가 취소되었습니다.", interrupted);
        } finally {
            exportProcess.compareAndSet(process, null);
        }
        if (closed) throw new IOException("Formatter is stopped");
        if (!before.equals(fingerprint())) throw new IOException("설정을 가져오는 동안 빌드 파일이 변경되었습니다. 다시 저장하세요.");
        Properties imported = new Properties();
        try (InputStream in = Files.newInputStream(output.resolve("snapshot.properties"))) {
            imported.load(in);
        }
        List<Path> roots = new ArrayList<>();
        int count = Integer.parseInt(imported.getProperty("watch.count"));
        for (int i = 0; i < count; i++) roots.add(Path.of(imported.getProperty("watch." + i)));
        watchedRoots = roots;
        String importedFingerprint = fingerprint();
        WorkerClient next =
                new WorkerClient(java, engineDirectory, output.resolve("worker.log"), output, Duration.ofSeconds(15));
        worker = next;
        if (closed) {
            next.close();
            worker = null;
            throw new IOException("Formatter is stopped");
        }
        metadata = imported;
        fingerprint = importedFingerprint;
        imports++;
    }

    private String fingerprint() throws IOException {
        SortedSet<Path> paths = new TreeSet<>();
        for (Path watched : watchedRoots) collect(watched, !watched.startsWith(root), paths);
        for (Path extra : extraWatch) collect(extra, true, paths);
        String envHome = System.getenv("GRADLE_USER_HOME");
        Path gradleHome = envHome == null ? Path.of(System.getProperty("user.home"), ".gradle") : Path.of(envHome);
        paths.add(gradleHome.resolve("gradle.properties"));
        paths.add(gradleHome.resolve("init.gradle"));
        paths.add(gradleHome.resolve("init.gradle.kts"));
        collect(gradleHome.resolve("init.d"), true, paths);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Path path : paths) {
                digest.update(path.toString().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                if (Files.isRegularFile(path)) {
                    try (InputStream in = Files.newInputStream(path)) {
                        byte[] buffer = new byte[8192];
                        int n;
                        while ((n = in.read(buffer)) >= 0) digest.update(buffer, 0, n);
                    }
                } else digest.update((byte) 0xff);
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static void collect(Path start, boolean allFiles, Set<Path> selected) throws IOException {
        if (!Files.isDirectory(start)) {
            selected.add(start);
            return;
        }
        Files.walkFileTree(start, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                return !dir.equals(start) && IGNORED.contains(dir.getFileName().toString())
                        ? FileVisitResult.SKIP_SUBTREE
                        : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String name = file.getFileName().toString();
                List<String> parts = new ArrayList<>();
                start.relativize(file).forEach(p -> parts.add(p.toString()));
                if (allFiles
                        || name.endsWith(".gradle")
                        || name.endsWith(".gradle.kts")
                        || name.equals("gradle.properties")
                        || name.endsWith(".toml")
                        || name.equals(".editorconfig")
                        || name.equals(".gitattributes")
                        || name.equals("gradlew")
                        || name.equals("gradlew.bat")
                        || parts.contains("gradle")
                        || parts.contains("buildSrc")
                        || parts.contains("build-logic")) selected.add(file);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public synchronized int importCount() {
        return imports;
    }

    public long workerPid() {
        WorkerClient current = worker;
        return current == null ? -1 : current.pid();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").startsWith("Windows");
    }

    @Override
    public void close() {
        closed = true;
        Process process = exportProcess.getAndSet(null);
        if (process != null) process.destroyForcibly();
        WorkerClient current = worker;
        if (current != null) current.close();
    }
}
