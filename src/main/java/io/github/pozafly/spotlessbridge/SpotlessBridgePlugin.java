package io.github.pozafly.spotlessbridge;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.gradle.api.*;
import org.gradle.util.GradleVersion;

/** The root project owns the engine version and exports its own Spotless configuration. */
public final class SpotlessBridgePlugin implements Plugin<Project> {
    /** Creates the plugin instance instantiated by Gradle. */
    public SpotlessBridgePlugin() {}

    /**
     * Registers the daemon lifecycle and formatting tasks and installs the Spotless snapshot export script.
     *
     * @param project Gradle root project that owns the daemon configuration
     * @throws GradleException if the plugin is applied to a subproject
     */
    @Override
    public void apply(Project project) {
        if (project != project.getRootProject())
            throw new GradleException("Apply io.github.pozafly.spotlessbridge to the root project only");
        String gradleVersion = project.getGradle().getGradleVersion();
        GradleVersion baseVersion = GradleVersion.version(gradleVersion).getBaseVersion();
        if (baseVersion.compareTo(GradleVersion.version("8.14.3")) < 0
                || baseVersion.compareTo(GradleVersion.version("10.0")) >= 0)
            throw new GradleException("Spotless Bridge does not support Gradle " + gradleVersion
                    + ". Supported Gradle range: >= 8.14.3 and < 10.0. "
                    + "Use a Gradle wrapper in this range or update Spotless Bridge.");
        if (!java.util.List.of("8.14.3", "8.14.6", "9.0.0", "9.8.1").contains(gradleVersion))
            project.getLogger().warn("Spotless Bridge: Gradle {} is within the supported range but has not been verified. "
                    + "Verified Gradle versions: 8.14.3, 8.14.6, 9.0.0, 9.8.1.", gradleVersion);
        // AI hooks run the launcher in .gradle, so any Gradle run (a build or an IDE sync) makes it available.
        writeLaunchers(project.getProjectDir().toPath());
        List<Path> initialWatchRoots = new ArrayList<>();
        project.getRootProject().getAllprojects().forEach(p -> initialWatchRoots.add(p.getProjectDir().toPath()));
        project.getGradle().getIncludedBuilds().forEach(b -> initialWatchRoots.add(b.getProjectDir().toPath()));
        String beforeEvaluation;
        try {
            beforeEvaluation = GradleFormatter.fingerprint(project.getProjectDir().toPath().toRealPath(), initialWatchRoots, List.of());
        } catch (IOException e) {
            throw new GradleException("Cannot read Spotless configuration inputs", e);
        }
        project.getTasks().register("spotlessBridgeStart", task -> {
            task.setGroup(GROUP);
            task.setDescription("Export current Spotless settings and start or refresh the project daemon");
            task.notCompatibleWithConfigurationCache("Exports evaluated Spotless tasks");
            task.getExtensions().getExtraProperties().set("installSnapshot", (java.util.function.Consumer<Path>)
                    snapshot -> {
                        try {
                            if (!beforeEvaluation.equals(GradleFormatter.fingerprint(
                                    project.getProjectDir().toPath().toRealPath(), initialWatchRoots, List.of())))
                                throw new IOException("Build files changed while the Spotless configuration was loading; retry");
                            SharedDaemon.install(project.getProjectDir().toPath(), snapshot);
                            project.getLogger()
                                    .lifecycle(
                                            "Spotless Bridge pid={}",
                                            SharedDaemon.status(
                                                    project.getProjectDir().toPath()));
                        } catch (Exception e) {
                            throw new GradleException("Cannot start project daemon", e);
                        }
                    });
            task.doFirst(ignored -> {
                try {
                    SharedDaemon.state(project.getProjectDir().toPath());
                } catch (Exception e) {
                    throw new GradleException("Cannot prepare daemon state", e);
                }
            });
        });
        project.apply(Map.of("from", getClass().getResource("/export.gradle")));
        project.getTasks().register("spotlessBridgeApply", task -> {
            task.setGroup(GROUP);
            task.setDescription("Format all Spotless Java targets, or only -PspotlessBridgeFiles=<a.java,b.java | list file>,"
                    + " through the project daemon");
            task.notCompatibleWithConfigurationCache("Reads current project daemon metadata");
            task.dependsOn("spotlessBridgeStart");
            task.doLast(ignored -> {
                Path root = project.getProjectDir().toPath();
                try {
                    List<String> paths = requestedPaths(project, root);
                    Path list = Files.createTempFile(SharedDaemon.state(root), "apply-files-", ".txt");
                    try {
                        Files.write(list, paths, StandardCharsets.UTF_8);
                        SharedDaemon.ApplyResult result = SharedDaemon.apply(root, list);
                        project.getLogger().lifecycle("Spotless Bridge: formatted {} {}, {} changed{}",
                                result.formatted(), result.formatted() == 1 ? "file" : "files", result.changed(),
                                result.skipped() == 0 ? "" : ", " + result.skipped() + " skipped (not Spotless targets)");
                    } finally {
                        Files.deleteIfExists(list);
                    }
                } catch (NoSuchFileException e) {
                    throw new GradleException("Spotless Bridge: file not found: " + e.getFile());
                } catch (GradleException e) {
                    throw e;
                } catch (Exception e) {
                    throw new GradleException("Spotless Bridge apply failed: " + e.getMessage(), e);
                }
            });
        });
        for (String action : new String[] {"Status", "Stop"}) {
            project.getTasks().register("spotlessBridge" + action, task -> {
                task.setGroup(GROUP);
                task.setDescription(action.equals("Stop")
                        ? "Stop the project daemon and its formatter worker"
                        : "Print the project daemon PID, or -1 when it is not running");
                task.notCompatibleWithConfigurationCache("Uses project daemon state");
                task.doLast(ignored -> {
                    try {
                        Path root = project.getProjectDir().toPath();
                        if (action.equals("Stop")) SharedDaemon.stop(root);
                        else project.getLogger().lifecycle("Spotless Bridge pid={}", SharedDaemon.status(root));
                    } catch (Exception e) {
                        throw new GradleException("Daemon " + action + " failed", e);
                    }
                });
            });
        }
    }

    private static final String GROUP = "spotlessBridge";

    /**
     * Without -PspotlessBridgeFiles every Spotless Java target is formatted, matching spotlessApply.
     * The property takes comma-separated paths, or one UTF-8 file listing a path per line.
     */
    private static List<String> requestedPaths(Project project, Path root) throws Exception {
        Object property = project.findProperty("spotlessBridgeFiles");
        if (property == null) {
            List<String> targets = new ArrayList<>();
            for (String key : SharedDaemon.metadata(SharedDaemon.endpoint(root)).stringPropertyNames())
                if (key.startsWith("target.")) targets.add(key.substring("target.".length()));
            Collections.sort(targets);
            return targets;
        }
        List<String> paths = new ArrayList<>();
        for (String path : property.toString().split(",")) if (!path.isBlank()) paths.add(path.strip());
        if (paths.isEmpty()) throw new GradleException("Spotless Bridge: -PspotlessBridgeFiles is empty. "
                + "Omit it to format every Spotless target, or pass paths such as -PspotlessBridgeFiles=src/main/java/A.java");
        Path single = project.file(paths.get(0)).toPath();
        if (paths.size() == 1 && !paths.get(0).endsWith(".java") && Files.isRegularFile(single))
            return Files.readAllLines(single, StandardCharsets.UTF_8);
        return paths;
    }

    private static final List<String> LAUNCHERS = List.of("spotless-bridge", "spotless-bridge.cmd");

    /**
     * Keeps the hook launchers in .gradle/spotless-bridge identical to this plugin version's copies.
     * A rewritten launcher may follow an older engine (0.1.0 does not accept --hook), so the recorded
     * client is dropped and the next hook call prepares this plugin's engine through Gradle once.
     */
    private static void writeLaunchers(Path root) {
        try {
            Path state = SharedDaemon.state(root);
            boolean changed = false;
            for (String name : LAUNCHERS) {
                byte[] content;
                try (InputStream input = SpotlessBridgePlugin.class.getResourceAsStream("/spotless-bridge/" + name)) {
                    if (input == null) throw new IOException("Missing packaged resource spotless-bridge/" + name);
                    content = input.readAllBytes();
                }
                Path launcher = state.resolve(name);
                if (Files.isRegularFile(launcher) && Arrays.equals(Files.readAllBytes(launcher), content)) continue;
                Path temporary = Files.createTempFile(state, name, ".tmp");
                Files.write(temporary, content);
                Files.move(temporary, launcher, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                changed = true;
            }
            Path shellLauncher = state.resolve(LAUNCHERS.get(0));
            if (!Files.isExecutable(shellLauncher)) shellLauncher.toFile().setExecutable(true);
            String os = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT);
            os = os.startsWith("windows") ? "windows" : os.startsWith("mac") ? "macos" : os.equals("linux") ? "linux" : "unsupported";
            String arch = System.getProperty("os.arch");
            arch = arch.equals("amd64") || arch.equals("x86_64") ? "x64"
                    : arch.equals("aarch64") || arch.equals("arm64") ? "arm64" : "unsupported";
            String executable = os.equals("windows") ? "spotless-bridge.exe" : "spotless-bridge";
            Path pointer = state.resolve("native-client.txt");
            try (InputStream input = SpotlessBridgePlugin.class.getResourceAsStream(
                    "/spotless-bridge/native/" + os + "-" + arch + "/" + executable)) {
                if (input == null) {
                    // Unsupported platforms keep the portable Java launcher.
                    Files.deleteIfExists(pointer);
                } else {
                    byte[] binary = input.readAllBytes();
                    String hash;
                    try {
                        hash = java.util.HexFormat.of().formatHex(
                                java.security.MessageDigest.getInstance("SHA-256").digest(binary));
                    } catch (java.security.NoSuchAlgorithmException impossible) {
                        throw new AssertionError(impossible);
                    }
                    Path target = state.resolve("native/" + hash + "/" + executable);
                    if (!Files.isRegularFile(target) || !Arrays.equals(Files.readAllBytes(target), binary)) {
                        Files.createDirectories(target.getParent());
                        Path temp = Files.createTempFile(target.getParent(), "cli-", ".tmp");
                        Files.write(temp, binary);
                        temp.toFile().setExecutable(true);
                        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    }
                    if (!Files.isExecutable(target)) target.toFile().setExecutable(true);
                    String value = state.relativize(target) + "\n";
                    if (!Files.isRegularFile(pointer) || !Files.readString(pointer).equals(value)) {
                        Path temp = Files.createTempFile(state, "native-client-", ".tmp");
                        Files.writeString(temp, value, StandardCharsets.UTF_8);
                        Files.move(temp, pointer, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                        changed = true;
                    }
                }
            }
            if (changed) Files.deleteIfExists(state.resolve("client-runtime.txt"));
        } catch (IOException e) {
            throw new GradleException("Cannot write the Spotless Bridge hook launchers", e);
        }
    }
}
