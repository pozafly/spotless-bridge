package io.github.pozafly.spotlessbridge;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Owns one child process. Requests are serialized; close/timeout can stop a blocked request. */
public final class WorkerClient implements AutoCloseable {
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private final Path javaExecutable;
    private final Path engineDirectory;
    private final Path errorLog;
    private final Path snapshotDirectory;
    private final Duration timeout;
    private final AtomicReference<Session> active = new AtomicReference<>();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "Spotless Bridge timeout");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean closed;

    /**
     * Creates a client that starts its formatter child process on the first request.
     *
     * @param javaExecutable Java executable used to start the worker
     * @param engineDirectory engine directory containing the worker's {@code lib} classpath
     * @param errorLog file to which worker standard error is appended
     * @param snapshotDirectory exported formatter snapshot loaded by the worker
     * @param timeout deadline for worker startup and each formatting response
     */
    public WorkerClient(
            Path javaExecutable, Path engineDirectory, Path errorLog, Path snapshotDirectory, Duration timeout) {
        this.javaExecutable = javaExecutable;
        this.engineDirectory = engineDirectory;
        this.errorLog = errorLog;
        this.snapshotDirectory = snapshotDirectory;
        this.timeout = timeout;
    }

    /**
     * Formats a buffer through the child process without writing the source file.
     * Requests are serialized; a failed transport or timeout stops the worker so a later request can restart it.
     *
     * @param sourceFile source path used to select formatters from the snapshot
     * @param source text to format, limited to 16 MiB when encoded as UTF-8
     * @return formatted text returned by the worker
     * @throws SourceException if the worker reports a formatter failure while keeping its session available
     * @throws IOException if the client is closed, the size limit is exceeded, or worker communication fails
     */
    public synchronized String format(Path sourceFile, String source) throws IOException {
        if (closed) throw new IOException("Formatter has been stopped");
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IOException("Java file exceeds the 16 MiB limit");
        Session session = active.get();
        if (session == null || !session.process.isAlive()) {
            if (session != null) stop(session);
            Process process = new ProcessBuilder(
                            javaExecutable.toString(),
                            "-Xmx256m",
                            "-cp",
                            "lib/*",
                            "io.github.pozafly.spotlessbridge.Worker",
                            snapshotDirectory.toUri().toASCIIString())
                    .directory(engineDirectory.toFile())
                    .redirectError(ProcessBuilder.Redirect.appendTo(errorLog.toFile()))
                    .start();
            session = new Session(process);
            active.set(session);
            if (closed) {
                stop(session);
                throw new IOException("Formatter has been stopped");
            }
        }
        Session current = session;
        AtomicBoolean timedOut = new AtomicBoolean();
        ScheduledFuture<?> deadline;
        try {
            deadline = timer.schedule(
                    () -> {
                        timedOut.set(true);
                        stop(current);
                    },
                    timeout.toMillis(),
                    TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException stopped) {
            stop(current);
            throw new IOException("Formatter has been stopped", stopped);
        }
        try {
            if (!current.ready) {
                if (current.input.readInt() != 0x534C4431) throw new IOException("Unsupported formatter protocol");
                current.ready = true;
            }
            current.output.writeInt(bytes.length);
            current.output.writeUTF(sourceFile.toFile().getCanonicalPath());
            current.output.write(bytes);
            current.output.flush();
            int status = current.input.readInt();
            int length = current.input.readInt();
            if (length < 0 || length > MAX_BYTES) throw new IOException("Invalid response length");
            byte[] result = current.input.readNBytes(length);
            if (result.length != length) throw new EOFException("Incomplete formatter response");
            String text = new String(result, StandardCharsets.UTF_8);
            if (status == 1) throw new SourceException(text);
            if (status != 0) throw new IOException("Unknown formatter response");
            if (timedOut.get()) throw new IOException("Formatter timed out");
            return text;
        } catch (SourceException invalidSource) {
            throw invalidSource;
        } catch (IOException failure) {
            stop(current);
            throw new IOException(
                    timedOut.get()
                            ? "Formatter timed out after " + timeout.toSeconds() + " seconds"
                            : "Formatter process failed. Check " + errorLog,
                    failure);
        } finally {
            deadline.cancel(false);
        }
    }

    /**
     * Returns the process ID recorded in the current worker session without checking process liveness.
     *
     * @return worker process ID, or {@code -1} if there is no session
     */
    public long pid() {
        Session session = active.get();
        return session == null ? -1 : session.process.pid();
    }

    private void stop(Session session) {
        active.compareAndSet(session, null);
        session.process.destroyForcibly();
    }

    /**
     * Closes this client, terminates its child process, and shuts down its timeout scheduler.
     */
    @Override
    public void close() {
        closed = true;
        Session session = active.getAndSet(null);
        if (session != null) session.process.destroyForcibly();
        timer.shutdownNow();
    }

    /**
     * A formatter failure reported in a complete worker response, allowing the worker session to remain in use.
     */
    public static final class SourceException extends IOException {
        /**
         * Creates an exception carrying the worker's formatter diagnostic.
         *
         * @param message formatter failure description
         */
        public SourceException(String message) {
            super(message);
        }
    }

    private static final class Session {
        final Process process;
        final DataInputStream input;
        final DataOutputStream output;
        boolean ready;

        Session(Process process) {
            this.process = process;
            input = new DataInputStream(new BufferedInputStream(process.getInputStream()));
            output = new DataOutputStream(new BufferedOutputStream(process.getOutputStream()));
        }
    }
}
