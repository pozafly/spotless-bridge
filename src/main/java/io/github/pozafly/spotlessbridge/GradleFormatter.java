package io.github.pozafly.spotlessbridge;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Evaluates Gradle only on the first request, changed configuration, or a newly encountered target. */
public final class GradleFormatter implements AutoCloseable {
    private static final Set<String> IGNORED =
            Set.of(".git", ".idea", ".gradle", "build", "out", "node_modules", ".venv");
    private final Path root, javaHome, cacheDirectory;
    private final List<Path> extraWatch;
    private final Runnable importing;
    private final AtomicReference<Process> exportProcess = new AtomicReference<>();
    private volatile boolean closed;
    private String fingerprint;
    private List<Path> watchedRoots;
    private Properties metadata;
    private final Set<Path> excluded = new HashSet<>();
    private int imports;

    /**
     * Creates a client for a project's shared daemon. Gradle evaluation is deferred until a request needs it.
     *
     * @param root existing Gradle root project directory
     * @param javaHome JDK home used to launch the project's Gradle wrapper
     * @param extraWatch additional configuration paths watched by this client
     * @param importing callback invoked before this client evaluates Gradle
     * @throws IOException if the project root cannot be resolved or daemon state cannot be created
     */
    public GradleFormatter(Path root, Path javaHome, List<Path> extraWatch, Runnable importing) throws IOException {
        this.root = root.toRealPath();
        this.javaHome = javaHome;
        this.cacheDirectory = SharedDaemon.state(this.root);
        this.extraWatch = List.copyOf(extraWatch);
        this.importing = importing;
        watchedRoots = List.of(this.root);
    }

    /**
     * Formats an in-memory buffer using the project's current Spotless configuration without writing the file.
     *
     * @param file source file path used to select the applicable formatters
     * @param source current editor buffer
     * @return formatted text, or the original text if the file is not a Spotless target
     * @throws IOException if the client is closed, configuration loading or formatting fails,
     *     or configuration changes during formatting
     */
    public synchronized String format(Path file, String source) throws IOException {
        if (closed) throw new IOException("Formatter is stopped");
        Path canonical = file.toFile().getCanonicalFile().toPath();
        Properties endpoint = prepare(canonical);
        if (!metadata.containsKey("target." + canonical)) {
            // A path requested before its first save must not become a cached exclusion.
            if (Files.isRegularFile(canonical)) excluded.add(canonical);
            return source;
        }
        fingerprint = fingerprint();
        String formatted = SharedDaemon.format(endpoint, canonical, source);
        if (closed || (fingerprint != null && !fingerprint.equals(fingerprint())))
            throw new IOException("Spotless 설정이 포맷 도중 변경되었습니다. 다시 저장하세요.");
        return formatted;
    }

    private Properties prepare(Path canonical) throws IOException {
        String current = fingerprint();
        Properties endpoint = null;
        try {
            endpoint = SharedDaemon.endpoint(root);
            Properties latest = SharedDaemon.metadata(endpoint);
            watchedRoots = SharedDaemon.watchedRoots(latest);
            if (metadata == null || !metadata.getProperty("fingerprint").equals(latest.getProperty("fingerprint")))
                excluded.clear();
            metadata = latest;
            boolean unknown = !metadata.containsKey("target." + canonical) && !excluded.contains(canonical);
            boolean changed = !metadata.getProperty("fingerprint").equals(fingerprint(root, watchedRoots, List.of()))
                    || (fingerprint != null && !current.equals(fingerprint));
            if (unknown || changed || SharedDaemon.status(root) < 0) endpoint = null;
        } catch (IOException unavailable) {
            endpoint = null;
        }
        if (endpoint == null) {
            // Metadata may add external included builds to the watched roots. Compare the same scope before/after Gradle.
            reload(fingerprint());
            endpoint = SharedDaemon.endpoint(root);
        }
        return endpoint;
    }

    /**
     * Invalidates this client's configuration and exclusion cache so the next request reevaluates Gradle.
     * The shared daemon remains running.
     */
    public synchronized void refresh() {
        fingerprint = "";
        excluded.clear();
    }

    /**
     * Runs the project-installed hook client and formats the given files on disk.
     * Known targets reuse the daemon while its configuration remains valid.
     *
     * @param args {@code --hook}, the Gradle root directory and the files to format, relative to the root or
     *     absolute; or, for hook scripts before 0.1.1, the Gradle root followed by a UTF-8 file listing one
     *     source path per line
     * @throws Exception if configuration preparation, file access, or formatting fails
     */
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--hook")) {
            if (args.length < 2) throw new IllegalArgumentException("Usage: --hook <gradle-root> [files...]");
            Path root = Path.of(args[1]).toRealPath();
            if (args.length == 2) return;
            StringBuilder paths = new StringBuilder();
            for (int i = 2; i < args.length; i++) {
                Path file = root.resolve(args[i]).toRealPath();
                if (file.toString().contains("\n") || file.toString().contains("\r"))
                    throw new IllegalArgumentException("Newlines in file names are not supported: " + file);
                paths.append(file).append('\n');
            }
            // The daemon protocol takes a list file; it also keeps spaces and commas in paths intact.
            Path list = Files.createTempFile(SharedDaemon.state(root), "hook-files-", ".txt");
            try {
                Files.writeString(list, paths, StandardCharsets.UTF_8);
                format(root, list);
            } finally {
                Files.deleteIfExists(list);
            }
            return;
        }
        format(Path.of(args[0]).toRealPath(), Path.of(args[1]));
    }

    private static void format(Path root, Path list) throws Exception {
        try (GradleFormatter client =
                new GradleFormatter(root, Path.of(System.getProperty("java.home")), List.of(), () -> {})) {
            for (String path : Files.readAllLines(list, StandardCharsets.UTF_8)) {
                if (!path.isBlank()) client.prepare(root.resolve(path).toRealPath());
            }
            SharedDaemon.apply(root, list);
        }
    }

    private void reload(String before) throws IOException {
        fingerprint = null;
        metadata = null;
        excluded.clear();
        importing.run();
        Path log = Files.createTempFile(cacheDirectory, "gradle-start-", ".log");
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
                        ":spotlessBridgeStart")
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
                throw new IOException("Spotless Bridge 설정을 120초 안에 가져오지 못했습니다. 로그: " + log);
            }
            String versionError = "";
            for (String line : Files.readAllLines(log)) {
                String message = line.strip();
                if (message.startsWith("Spotless Bridge:")) System.err.println(message);
                if (message.contains("Spotless Bridge does not support")) versionError = message;
            }
            if (process.exitValue() != 0)
                throw new IOException("Spotless Bridge 설정을 가져오지 못했습니다. 이전 규칙은 적용하지 않습니다. 로그: " + log
                        + (versionError.isEmpty() ? "" : "\n" + versionError));
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Spotless Bridge 설정 불러오기가 취소되었습니다.", interrupted);
        } finally {
            exportProcess.compareAndSet(process, null);
        }
        if (closed) throw new IOException("Formatter is stopped");
        if (!before.equals(fingerprint())) throw new IOException("설정을 가져오는 동안 빌드 파일이 변경되었습니다. 다시 저장하세요.");
        Properties imported = SharedDaemon.metadata(SharedDaemon.endpoint(root));
        watchedRoots = SharedDaemon.watchedRoots(imported);
        metadata = imported;
        fingerprint = fingerprint();
        imports++;
    }

    private String fingerprint() throws IOException {
        return fingerprint(root, watchedRoots, extraWatch);
    }

    /**
     * Hashes the tracked configuration paths and their contents, including Gradle user-home properties and init scripts.
     *
     * @param root canonical Gradle root directory used to distinguish internal and external build roots
     * @param watchedRoots project and included-build directories recorded in the snapshot
     * @param extraWatch additional configuration files or directories to inspect
     * @return hexadecimal SHA-256 fingerprint of the tracked inputs
     * @throws IOException if a tracked path cannot be traversed or read
     */
    public static String fingerprint(Path root, List<Path> watchedRoots, List<Path> extraWatch) throws IOException {
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

    /**
     * Returns the number of Gradle configuration reloads completed by this client.
     *
     * @return this client's completed reload count
     */
    public synchronized int importCount() {
        return imports;
    }

    /**
     * Queries the shared daemon for its current formatter worker process.
     *
     * @return worker process ID, or {@code -1} if no worker is registered or the daemon cannot be queried
     */
    public long workerPid() {
        try {
            return SharedDaemon.workerPid(root);
        } catch (IOException stopped) {
            return -1;
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").startsWith("Windows");
    }

    /**
     * Closes this client and stops any Gradle export process it owns.
     * The project daemon remains available to other clients.
     */
    @Override
    public void close() {
        closed = true;
        Process process = exportProcess.getAndSet(null);
        if (process != null) process.destroyForcibly();
        // The project daemon belongs to all clients; closing an editor must not stop it.
    }
}
