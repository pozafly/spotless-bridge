package io.github.pozafly.spotlessbridge;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Project-local authenticated transport. Only Gradle installs snapshots; the server never runs Gradle. */
public final class SharedDaemon {
    private static final int MAGIC = 0x534C4432, MAX = 16 * 1024 * 1024;
    private static final String PROTOCOL = "2";

    /** Creates a stateless helper; daemon operations are exposed through static methods. */
    public SharedDaemon() {}

    /**
     * Creates the project-local daemon state and snapshot directories if needed.
     *
     * @param root existing Gradle root directory
     * @return canonical project-local daemon state directory
     * @throws IOException if the root cannot be resolved or state directories cannot be created or secured
     */
    public static Path state(Path root) throws IOException {
        Path state = root.toRealPath().resolve(".gradle/spotless-bridge");
        Files.createDirectories(state.resolve("snapshots"));
        try {
            Files.setPosixFilePermissions(state, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException ignored) {
            /* Windows uses the user's directory ACL. */
        }
        return state;
    }

    /**
     * Loads a Java properties file.
     *
     * @param path properties file to read
     * @return loaded properties
     * @throws IOException if the file cannot be opened or read
     */
    public static Properties read(Path path) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            properties.load(in);
        }
        return properties;
    }

    private static void write(Path path, Properties properties) throws IOException {
        Path temp = Files.createTempFile(path.getParent(), "state-", ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(temp)) {
                properties.store(out, "Spotless Bridge");
            }
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /**
     * Reads the saved endpoint and checks its protocol and project identity without testing server liveness.
     *
     * @param root Gradle root directory
     * @return saved daemon connection properties
     * @throws IOException if the endpoint cannot be read or belongs to an incompatible protocol or project
     */
    public static Properties endpoint(Path root) throws IOException {
        Properties endpoint = read(root.toRealPath().resolve(".gradle/spotless-bridge/endpoint.properties"));
        if (!PROTOCOL.equals(endpoint.getProperty("protocol"))
                || !root.toRealPath().toString().equals(endpoint.getProperty("root")))
            throw new IOException("Incompatible project daemon endpoint; run spotlessBridgeStart");
        return endpoint;
    }

    /**
     * Loads the snapshot metadata referenced by an endpoint.
     *
     * @param endpoint daemon connection properties containing the snapshot path
     * @return exported snapshot metadata
     * @throws IOException if the metadata cannot be read
     */
    public static Properties metadata(Properties endpoint) throws IOException {
        return read(Path.of(endpoint.getProperty("snapshot"), "snapshot.properties"));
    }

    /**
     * Installs an exported snapshot, starting or reusing the project daemon and updating its client runtime.
     * Called inside Gradle after evaluation and export of the complete current target set.
     *
     * @param root Gradle root directory
     * @param snapshot directory containing the exported formatters and target metadata
     * @throws Exception if snapshot installation or daemon startup or communication fails
     */
    public static synchronized void install(Path root, Path snapshot) throws Exception {
        root = root.toRealPath();
        Path state = state(root);
        Properties metadata = read(snapshot.resolve("snapshot.properties"));
        metadata.setProperty("fingerprint", GradleFormatter.fingerprint(root, watchedRoots(metadata), List.of()));
        write(snapshot.resolve("snapshot.properties"), metadata);
        String key = snapshotKey(snapshot, metadata);
        Path artifact = Path.of(SharedDaemon.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());
        if (!Files.isRegularFile(artifact))
            throw new IOException("Daemon runtime must be loaded from its published plugin JAR");
        String engineId =
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(artifact)));
        Path engine = state.resolve("runtime/" + engineId);
        String javaHome = Path.of(System.getProperty("java.home")).toRealPath().toString();
        // Cross-process lock covers discovery, startup, readiness and refresh. Daemon has a separate lifetime lock.
        try (FileChannel channel = FileChannel.open(
                        state.resolve("start.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock lock = channel.lock()) {
            Files.createDirectories(engine.resolve("lib"));
            Path jar = engine.resolve("lib/engine.jar");
            if (!Files.exists(jar)) Files.copy(artifact, jar);
            Properties existing = null;
            try {
                existing = endpoint(root);
                request(existing, "ping", "", "", "");
            } catch (IOException stopped) {
                existing = null;
            }
            if (existing != null
                    && (!engineId.equals(existing.getProperty("engine"))
                            || !javaHome.equals(existing.getProperty("javaHome")))) {
                request(existing, "stop", "", "", "");
                waitStopped(existing);
                existing = null;
            }
            if (existing == null) {
                String token = UUID.randomUUID().toString() + UUID.randomUUID();
                Path launch = Files.createTempFile(state, "launch-", ".token");
                Files.writeString(launch, token);
                Process process = new ProcessBuilder(
                                Path.of(javaHome, "bin", windows() ? "java.exe" : "java")
                                        .toString(),
                                "-Xmx128m",
                                "-cp",
                                jar.toString(),
                                SharedDaemon.class.getName(),
                                root.toString(),
                                engine.toString(),
                                engineId,
                                launch.toString(),
                                snapshot.toString(),
                                key,
                                System.getProperty("spotlessBridge.idleSeconds", "1800"))
                        .directory(root.toFile())
                        .redirectInput(ProcessBuilder.Redirect.from(new File(windows() ? "NUL" : "/dev/null")))
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.appendTo(
                                state.resolve("daemon.log").toFile()))
                        .start();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                while (System.nanoTime() < deadline && process.isAlive()) {
                    try {
                        Properties candidate = endpoint(root);
                        if (token.equals(candidate.getProperty("token"))) {
                            request(candidate, "ping", "", "", "");
                            existing = candidate;
                            break;
                        }
                    } catch (IOException waiting) {
                        /* atomic readiness publication is pending */
                    }
                    Thread.sleep(50);
                }
                Files.deleteIfExists(launch);
                if (existing == null) {
                    process.destroyForcibly();
                    throw new IOException("Daemon did not become ready; see " + state.resolve("daemon.log"));
                }
            } else {
                request(existing, "load", key, snapshot.toString(), "");
            }
            Path client = Files.createTempFile(state, "client-", ".tmp");
            try {
                Files.writeString(client, jar.toString() + "\n" + javaHome + "\n", StandardCharsets.UTF_8);
                Files.move(
                        client,
                        state.resolve("client-runtime.txt"),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(client);
            }
            // An unchanged export is not retained: repeated hooks must not fill the project cache.
            if (!snapshot.equals(Path.of(endpoint(root).getProperty("snapshot")))) {
                try (var paths = Files.walk(snapshot)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
                }
            }
        }
    }

    private static String snapshotKey(Path snapshot, Properties metadata) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (String name : new TreeSet<>(metadata.stringPropertyNames())) {
            digest.update((name + "=" + metadata.getProperty(name) + "\n").getBytes(StandardCharsets.UTF_8));
        }
        for (int i = 0; i < Integer.parseInt(metadata.getProperty("formatters")); i++) {
            digest.update(Files.readAllBytes(snapshot.resolve("formatter-" + i + "/formatter.ser")));
            digest.update(Files.readAllBytes(snapshot.resolve("formatter-" + i + "/classpath.txt")));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * Reads the configuration watch roots exported into a snapshot.
     *
     * @param metadata valid snapshot metadata containing the watch entries
     * @return project and included-build paths recorded in the snapshot
     */
    public static List<Path> watchedRoots(Properties metadata) {
        List<Path> roots = new ArrayList<>();
        for (int i = 0; i < Integer.parseInt(metadata.getProperty("watch.count")); i++)
            roots.add(Path.of(metadata.getProperty("watch." + i)));
        return roots;
    }

    /**
     * Sends an in-memory buffer to the daemon using the endpoint's snapshot key without writing the source file.
     *
     * @param endpoint saved daemon connection properties
     * @param file source path used to select formatters
     * @param source text to format
     * @return formatted text returned by the daemon
     * @throws IOException if path resolution, communication, snapshot validation, or formatting fails
     */
    public static String format(Properties endpoint, Path file, String source) throws IOException {
        return request(
                endpoint, "format", endpoint.getProperty("key"), file.toFile().getCanonicalPath(), source);
    }

    /**
     * Pings the project's saved daemon endpoint.
     *
     * @param root Gradle root directory
     * @return daemon process ID, or {@code -1} if the endpoint is missing or the connection is refused
     * @throws IOException if another endpoint or communication error prevents the status check
     */
    public static long status(Path root) throws IOException {
        try {
            return Long.parseLong(request(endpoint(root), "ping", "", "", ""));
        } catch (NoSuchFileException | ConnectException stopped) {
            return -1;
        }
    }

    /**
     * Requests an authenticated daemon shutdown and waits for it to finish.
     * A missing endpoint or refused connection is treated as an already stopped daemon.
     *
     * @param root Gradle root directory
     * @throws Exception if communication fails or the daemon does not stop within the timeout
     */
    public static void stop(Path root) throws Exception {
        Properties endpoint;
        try {
            endpoint = endpoint(root);
            request(endpoint, "stop", "", "", "");
        } catch (NoSuchFileException | ConnectException stopped) {
            return;
        }
        waitStopped(endpoint);
    }

    private static void waitStopped(Properties endpoint) throws Exception {
        // Never kill a PID from a state file: it may have been recycled by the OS.
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < end) {
            try {
                request(endpoint, "ping", "", "", "");
            } catch (IOException stopped) {
                return;
            }
            Thread.sleep(50);
        }
        throw new IOException("Daemon did not stop");
    }

    private static String request(Properties endpoint, String action, String key, String path, String text)
            throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(
                    new InetSocketAddress(
                            InetAddress.getLoopbackAddress(), Integer.parseInt(endpoint.getProperty("port"))),
                    2000);
            socket.setSoTimeout(30000);
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            out.writeInt(MAGIC);
            out.writeUTF(endpoint.getProperty("token"));
            out.writeUTF(action);
            out.writeUTF(key);
            out.writeUTF(path);
            payload(out, text);
            out.flush();
            int status = in.readInt();
            String result = payload(in);
            if (status == 1) throw new WorkerClient.SourceException(result);
            if (status != 0) throw new IOException(result);
            return result;
        }
    }

    private static String payload(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > MAX) throw new IOException("Invalid payload length (maximum 16 MiB)");
        byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) throw new EOFException("Truncated payload");
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void payload(DataOutputStream out, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX) throw new IOException("Payload exceeds 16 MiB");
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    /**
     * Formats listed files on disk using an already installed daemon snapshot.
     * Each changed file is written after checking its original contents and tracked configuration;
     * files written before a later failure are not rolled back. Non-target files are skipped.
     *
     * @param root Gradle root directory
     * @param list UTF-8 file with one absolute or root-relative source path per line
     * @throws Exception if file access, formatting, encoding, or concurrent-change validation fails
     */
    public static void apply(Path root, Path list) throws Exception {
        Properties endpoint = endpoint(root);
        Properties metadata = metadata(endpoint);
        String fingerprint = metadata.getProperty("fingerprint");
        for (String line : Files.readAllLines(list, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            Path file = root.resolve(line).toRealPath();
            String ids = metadata.getProperty("target." + file);
            if (ids == null) continue;
            Charset encoding = Charset.forName(metadata.getProperty("encoding." + ids.split(",")[0]));
            for (String id : ids.split(",")) {
                if (!encoding.equals(Charset.forName(metadata.getProperty("encoding." + id))))
                    throw new IOException("Overlapping Spotless targets use different encodings: " + file);
            }
            byte[] original = Files.readAllBytes(file);
            String source =
                    encoding.newDecoder().decode(ByteBuffer.wrap(original)).toString();
            String formatted = format(endpoint, file, source);
            ByteBuffer encoded = encoding.newEncoder().encode(java.nio.CharBuffer.wrap(formatted));
            byte[] result = new byte[encoded.remaining()];
            encoded.get(result);
            if (!fingerprint.equals(GradleFormatter.fingerprint(root.toRealPath(), watchedRoots(metadata), List.of())))
                throw new IOException("Build settings changed during formatting; retry");
            if (!Arrays.equals(original, Files.readAllBytes(file)))
                throw new IOException("Source changed during formatting: " + file);
            if (!Arrays.equals(original, result)) Files.write(file, result);
        }
    }

    /**
     * Runs the authenticated loopback server. This internal entry point is launched by {@link #install}.
     *
     * @param args root directory, engine directory, engine identifier, one-use token file,
     *     snapshot directory, snapshot key, and idle timeout in seconds, in that order
     * @throws Exception if server setup, snapshot loading, or server operation fails
     */
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]), engine = Path.of(args[1]);
        Path state = state(root), endpointPath = state.resolve("endpoint.properties");
        String token = Files.readString(Path.of(args[3]));
        Files.delete(Path.of(args[3]));
        Properties endpoint = new Properties();
        endpoint.setProperty("root", root.toString());
        endpoint.setProperty("engine", args[2]);
        endpoint.setProperty("token", token);
        endpoint.setProperty("snapshot", args[4]);
        endpoint.setProperty("key", args[5]);
        endpoint.setProperty("protocol", PROTOCOL);
        endpoint.setProperty("pid", Long.toString(ProcessHandle.current().pid()));
        endpoint.setProperty(
                "javaHome",
                Path.of(System.getProperty("java.home")).toRealPath().toString());
        long idleNanos = TimeUnit.SECONDS.toNanos(Long.parseLong(args[6]));
        WorkerClient worker = null;
        try (FileChannel channel = FileChannel.open(
                        state.resolve("daemon.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock lock = channel.lock()) {
            if (lock == null) throw new IOException("Another project daemon already owns the lifetime lock");
            try (ServerSocket server = new ServerSocket(0, 64, InetAddress.getLoopbackAddress())) {
                server.setSoTimeout(1000);
                endpoint.setProperty("port", Integer.toString(server.getLocalPort()));
                write(endpointPath, endpoint);
                long lastRequest = System.nanoTime();
                while (System.nanoTime() - lastRequest < idleNanos) {
                    try (Socket client = server.accept()) {
                        client.setSoTimeout(5000);
                        DataInputStream in = new DataInputStream(new BufferedInputStream(client.getInputStream()));
                        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(client.getOutputStream()));
                        if (in.readInt() != MAGIC
                                || !MessageDigest.isEqual(
                                        in.readUTF().getBytes(StandardCharsets.UTF_8),
                                        token.getBytes(StandardCharsets.UTF_8))) continue;
                        String action = in.readUTF(), key = in.readUTF(), path = in.readUTF(), text = payload(in);
                        lastRequest = System.nanoTime();
                        int status = 0;
                        String result = "";
                        boolean stop = false;
                        try {
                            switch (action) {
                                case "ping" -> result = endpoint.getProperty("pid");
                                case "stop" -> stop = true;
                                case "load" -> {
                                    if (!key.equals(endpoint.getProperty("key"))) {
                                        Path snapshot = Path.of(path).toRealPath();
                                        if (!snapshot.startsWith(state.resolve("snapshots")))
                                            throw new IOException("Invalid snapshot location");
                                        if (worker != null) worker.close();
                                        worker = null;
                                        endpoint.setProperty("snapshot", snapshot.toString());
                                        endpoint.setProperty("key", key);
                                        write(endpointPath, endpoint);
                                    }
                                }
                                case "format" -> {
                                    if (!key.equals(endpoint.getProperty("key")))
                                        throw new IOException("Daemon configuration changed; retry formatting");
                                    if (worker == null)
                                        worker = new WorkerClient(
                                                Path.of(
                                                        System.getProperty("java.home"),
                                                        "bin",
                                                        windows() ? "java.exe" : "java"),
                                                engine,
                                                state.resolve("worker.log"),
                                                Path.of(endpoint.getProperty("snapshot")),
                                                Duration.ofSeconds(15));
                                    result = worker.format(Path.of(path), text);
                                }
                                case "workerPid" -> result = Long.toString(worker == null ? -1 : worker.pid());
                                default -> throw new IOException("Unknown daemon operation");
                            }
                        } catch (WorkerClient.SourceException failure) {
                            status = 1;
                            result = failure.getMessage();
                        } catch (Exception failure) {
                            status = 2;
                            result = failure.toString();
                        }
                        out.writeInt(status);
                        payload(out, result);
                        out.flush();
                        if (stop) break;
                    } catch (SocketTimeoutException idle) {
                        /* check idle deadline */
                    } catch (IOException badClient) {
                        System.err.println("Client request failed: " + badClient);
                    }
                }
            } finally {
                if (worker != null) worker.close();
                try {
                    if (token.equals(read(endpointPath).getProperty("token"))) Files.deleteIfExists(endpointPath);
                } catch (NoSuchFileException ignored) {
                }
            }
        }
    }

    /**
     * Queries the daemon's current formatter worker session.
     *
     * @param root Gradle root directory
     * @return worker process ID, or {@code -1} if no worker session exists
     * @throws IOException if the endpoint cannot be read or the daemon cannot be queried
     */
    public static long workerPid(Path root) throws IOException {
        return Long.parseLong(request(endpoint(root), "workerPid", "", "", ""));
    }

    private static boolean windows() {
        return System.getProperty("os.name").startsWith("Windows");
    }
}
