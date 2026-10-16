package io.github.pozafly.spotlessbridge;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.Arrays;
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
            state.resolve(LAUNCHERS.get(0)).toFile().setExecutable(true);
            if (changed) Files.deleteIfExists(state.resolve("client-runtime.txt"));
        } catch (IOException e) {
            throw new GradleException("Cannot write the Spotless Bridge hook launchers", e);
        }
    }
}
