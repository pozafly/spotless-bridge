package io.github.pozafly.spotlessbridge;

import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;

/** Loads only locally exported project formatters. No fixed Spotless or formatter version. */
public final class Worker {
    private static final int MAX_BYTES = 16 * 1024 * 1024;

    /** Creates an entry-point holder; use {@link #main} to run the worker protocol. */
    public Worker() {}

    /**
     * Loads exported project formatters and serves buffer-formatting requests over standard input and output.
     * Source files are not written. The worker exits when its input closes or its parent process exits.
     *
     * @param args one argument containing the exported snapshot directory
     * @throws Exception if snapshot loading, protocol processing, or formatter cleanup fails
     */
    public static void main(String[] args) throws Exception {
        // An abruptly killed daemon cannot run close(); its worker must not remain orphaned.
        ProcessHandle.current()
                .parent()
                .ifPresent(parent ->
                        parent.onExit().thenRun(() -> Runtime.getRuntime().halt(0)));
        Path snapshot = Path.of(args[0]);
        Properties metadata = new Properties();
        try (InputStream in = Files.newInputStream(snapshot.resolve("snapshot.properties"))) {
            metadata.load(in);
        }
        if (!"1".equals(metadata.getProperty("protocol")))
            throw new IOException("Unsupported Spotless Bridge snapshot");
        List<LoadedFormatter> formatters = new ArrayList<>();
        try {
            for (int i = 0; i < Integer.parseInt(metadata.getProperty("formatters")); i++) {
                formatters.add(new LoadedFormatter(snapshot.resolve("formatter-" + i)));
            }
            DataInputStream input = new DataInputStream(new BufferedInputStream(System.in));
            DataOutputStream output = new DataOutputStream(new BufferedOutputStream(System.out));
            output.writeInt(0x534C4431); // SLD1: source path + UTF-8 document buffer
            output.flush();
            while (true) {
                int size;
                try {
                    size = input.readInt();
                } catch (EOFException end) {
                    return;
                }
                if (size < 0 || size > MAX_BYTES) throw new IOException("Invalid input length");
                String path = input.readUTF();
                byte[] bytes = input.readNBytes(size);
                if (bytes.length != size) throw new EOFException("Truncated request");
                try {
                    String text = new String(bytes, StandardCharsets.UTF_8);
                    String selected = metadata.getProperty("target." + path);
                    if (selected != null) {
                        for (String id : selected.split(","))
                            text = formatters.get(Integer.parseInt(id)).format(path, text);
                    }
                    respond(output, 0, text.getBytes(StandardCharsets.UTF_8));
                } catch (Exception failure) {
                    Throwable cause = failure;
                    while (cause instanceof InvocationTargetException && cause.getCause() != null)
                        cause = cause.getCause();
                    String message =
                            cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
                    respond(output, 1, message.getBytes(StandardCharsets.UTF_8));
                }
            }
        } finally {
            for (LoadedFormatter formatter : formatters) formatter.close();
        }
    }

    private static final class LoadedFormatter implements AutoCloseable {
        final URLClassLoader loader;
        final Object formatter;
        final Charset encoding;
        final Method dirtyOf, isClean, didNotConverge, writeCanonical;

        LoadedFormatter(Path folder) throws Exception {
            String[] entries = Files.readString(folder.resolve("classpath.txt")).split(File.pathSeparator);
            URL[] urls = new URL[entries.length];
            for (int i = 0; i < entries.length; i++)
                urls[i] = Path.of(entries[i]).toUri().toURL();
            loader = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader());
            try (ObjectInputStream input =
                    new ObjectInputStream(Files.newInputStream(folder.resolve("formatter.ser"))) {
                        @Override
                        protected Class<?> resolveClass(ObjectStreamClass type)
                                throws IOException, ClassNotFoundException {
                            return Class.forName(type.getName(), false, loader);
                        }
                    }) {
                formatter = input.readObject();
            }
            Class<?> formatterType = loader.loadClass("com.diffplug.spotless.Formatter");
            Class<?> dirtyType = loader.loadClass("com.diffplug.spotless.DirtyState");
            encoding = (Charset) formatterType.getMethod("getEncoding").invoke(formatter);
            dirtyOf = dirtyType.getMethod("of", formatterType, File.class, byte[].class);
            isClean = dirtyType.getMethod("isClean");
            didNotConverge = dirtyType.getMethod("didNotConverge");
            writeCanonical = dirtyType.getMethod("writeCanonicalTo", OutputStream.class);
        }

        String format(String path, String text) throws Exception {
            ByteBuffer encoded = encoding.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(text));
            byte[] raw = new byte[encoded.remaining()];
            encoded.get(raw);
            Object dirty = dirtyOf.invoke(null, formatter, new File(path), raw);
            if ((boolean) didNotConverge.invoke(dirty)) throw new IOException("Formatter output did not converge");
            if ((boolean) isClean.invoke(dirty)) return text;
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            writeCanonical.invoke(dirty, output);
            return output.toString(encoding);
        }

        @Override
        public void close() throws Exception {
            try {
                ((AutoCloseable) formatter).close();
            } finally {
                loader.close();
            }
        }
    }

    private static void respond(DataOutputStream out, int status, byte[] payload) throws IOException {
        if (payload.length > MAX_BYTES) throw new IOException("Formatter response exceeds 16 MiB");
        out.writeInt(status);
        out.writeInt(payload.length);
        out.write(payload);
        out.flush();
    }
}
