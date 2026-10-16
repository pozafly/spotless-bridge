package io.github.pozafly.spotlessbridge;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
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
        project.getTasks().register("spotlessBridgeStart", task -> {
            task.setGroup("formatting");
            task.setDescription("Export current Spotless settings and start or refresh the project daemon");
            task.notCompatibleWithConfigurationCache("Exports evaluated Spotless tasks");
            task.getExtensions().getExtraProperties().set("installSnapshot", (java.util.function.Consumer<Path>)
                    snapshot -> {
                        try {
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
                warnIfHookOutdated(project);
            });
        });
        project.apply(Map.of("from", getClass().getResource("/export.gradle")));
        project.getTasks().register("spotlessBridgeApply", task -> {
            task.setGroup("formatting");
            task.setDescription("Format only paths in the UTF-8 file given by -PspotlessBridgeFiles");
            task.notCompatibleWithConfigurationCache("Reads current project daemon metadata");
            task.dependsOn("spotlessBridgeStart");
            task.doLast(ignored -> {
                Object list = project.findProperty("spotlessBridgeFiles");
                if (list == null)
                    throw new GradleException("Supply -PspotlessBridgeFiles=<UTF-8 path list, one file per line>");
                try {
                    SharedDaemon.apply(
                            project.getProjectDir().toPath(), project.file(list).toPath());
                } catch (Exception e) {
                    throw new GradleException("Spotless Bridge apply failed", e);
                }
            });
        });
        project.getTasks().register("spotlessBridgeInstallHook", task -> {
            task.setGroup("formatting");
            task.setDescription("Write the AI hook launchers to -PspotlessBridgeHookDir (default "
                    + DEFAULT_HOOK_DIRECTORY + ")");
            task.notCompatibleWithConfigurationCache("Writes into the project directory");
            task.doLast(ignored -> {
                try {
                    Path directory = hookDirectory(project);
                    Files.createDirectories(directory);
                    for (String name : LAUNCHERS) Files.write(directory.resolve(name), resource(name));
                    Files.write(directory.resolve("LICENSE"), resource("LICENSE"));
                    directory.resolve(LAUNCHERS.get(0)).toFile().setExecutable(true);
                    // The recorded client may belong to an older engine (0.1.0 does not accept --hook).
                    // Without it, the next hook call prepares this plugin's engine through Gradle once.
                    Files.deleteIfExists(project.getProjectDir().toPath().resolve(".gradle/spotless-bridge/client-runtime.txt"));
                    project.getLogger().lifecycle("Spotless Bridge: installed hook launchers in {}", directory);
                    if (Files.exists(directory.resolve(LEGACY_SCRIPT)))
                        project.getLogger().warn("Spotless Bridge: {} is no longer used. Point your AI hook at {} and delete it.",
                                directory.resolve(LEGACY_SCRIPT), directory.resolve(LAUNCHERS.get(0)));
                } catch (IOException e) {
                    throw new GradleException("Cannot install the Spotless Bridge hook launchers", e);
                }
            });
        });
        for (String action : new String[] {"Status", "Stop"}) {
            project.getTasks().register("spotlessBridge" + action, task -> {
                task.setGroup("formatting");
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

    private static final String DEFAULT_HOOK_DIRECTORY = "tools/spotless-bridge";
    private static final List<String> LAUNCHERS = List.of("spotless-bridge", "spotless-bridge.cmd");
    private static final String LEGACY_SCRIPT = "ai-hook.py";

    private static Path hookDirectory(Project project) {
        Object configured = project.findProperty("spotlessBridgeHookDir");
        return project.file(configured == null ? DEFAULT_HOOK_DIRECTORY : configured).toPath();
    }

    private static byte[] resource(String name) throws IOException {
        try (InputStream input = SpotlessBridgePlugin.class.getResourceAsStream("/spotless-bridge/" + name)) {
            if (input == null) throw new IOException("Missing packaged resource spotless-bridge/" + name);
            return input.readAllBytes();
        }
    }

    private static String normalized(byte[] content) {
        // Git may rewrite line endings on checkout; only the commands matter.
        return new String(content, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    /**
     * Launchers keep working across plugin versions because the engine holds the formatting logic.
     * Warn only when an installed launcher differs from the packaged one, or the pre-0.1.1 Python hook remains.
     */
    private static void warnIfHookOutdated(Project project) {
        Path directory = hookDirectory(project);
        try {
            for (String name : LAUNCHERS) {
                Path installed = directory.resolve(name);
                if (Files.isRegularFile(installed)
                        && !normalized(Files.readAllBytes(installed)).equals(normalized(resource(name))))
                    project.getLogger().warn("Spotless Bridge: {} differs from the launcher in this plugin version. "
                            + "Run ./gradlew spotlessBridgeInstallHook to update it.", installed);
            }
            if (Files.isRegularFile(directory.resolve(LEGACY_SCRIPT)))
                project.getLogger().warn("Spotless Bridge: {} is replaced by the launcher in this directory. "
                        + "Run ./gradlew spotlessBridgeInstallHook and point your AI hook at {}.",
                        directory.resolve(LEGACY_SCRIPT), directory.resolve(LAUNCHERS.get(0)));
        } catch (IOException e) {
            project.getLogger().warn("Spotless Bridge: cannot check the hook launchers: {}", e.getMessage());
        }
    }
}
