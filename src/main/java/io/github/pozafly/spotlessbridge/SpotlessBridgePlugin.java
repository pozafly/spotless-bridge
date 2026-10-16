package io.github.pozafly.spotlessbridge;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.gradle.api.*;

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
            task.setDescription("Write the AI hook script for this plugin version to -PspotlessBridgeHookDir"
                    + " (default " + DEFAULT_HOOK_DIRECTORY + ")");
            task.notCompatibleWithConfigurationCache("Writes into the project directory");
            task.doLast(ignored -> {
                try {
                    Path directory = hookDirectory(project);
                    Files.createDirectories(directory);
                    Path script = directory.resolve("ai-hook.py");
                    Files.write(script, resource("ai-hook.py"));
                    Files.write(directory.resolve("LICENSE"), resource("LICENSE"));
                    script.toFile().setExecutable(true);
                    project.getLogger().lifecycle("Spotless Bridge: installed hook script {} at {}", packagedVersion(), script);
                } catch (IOException e) {
                    throw new GradleException("Cannot install the Spotless Bridge hook script", e);
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
    private static final Pattern SCRIPT_VERSION = Pattern.compile("^BRIDGE_VERSION = '([^']*)'$", Pattern.MULTILINE);

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

    private static String scriptVersion(String script) {
        Matcher matcher = SCRIPT_VERSION.matcher(script);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String packagedVersion() throws IOException {
        return scriptVersion(new String(resource("ai-hook.py"), StandardCharsets.UTF_8));
    }

    /** A hook copied from another plugin version still works, but may miss fixes; point users at the install task. */
    private static void warnIfHookOutdated(Project project) {
        Path installed = hookDirectory(project).resolve("ai-hook.py");
        if (!Files.isRegularFile(installed)) return;
        try {
            String expected = packagedVersion();
            String actual = scriptVersion(Files.readString(installed, StandardCharsets.UTF_8));
            if (!expected.equals(actual))
                project.getLogger().warn("Spotless Bridge: {} is hook script version {} but the plugin is {}. "
                        + "Run ./gradlew spotlessBridgeInstallHook to update it.",
                        installed, actual == null || actual.startsWith("@") ? "unknown" : actual, expected);
        } catch (IOException e) {
            project.getLogger().warn("Spotless Bridge: cannot check hook script version: {}", e.getMessage());
        }
    }
}
