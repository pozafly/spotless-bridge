package io.github.pozafly.spotlessdaemon;

import java.nio.file.*;
import java.util.Map;
import org.gradle.api.*;

/** The root project owns the engine version and exports its own Spotless configuration. */
public final class SpotlessDaemonPlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        if (project != project.getRootProject())
            throw new GradleException("Apply io.github.pozafly.spotlessdaemon to the root project only");
        project.getTasks().register("spotlessDaemonStart", task -> {
            task.setGroup("formatting");
            task.setDescription("Export current Spotless settings and start or refresh the project daemon");
            task.notCompatibleWithConfigurationCache("Exports evaluated Spotless tasks");
            task.getExtensions().getExtraProperties().set("installSnapshot", (java.util.function.Consumer<Path>)
                    snapshot -> {
                        try {
                            SharedDaemon.install(project.getProjectDir().toPath(), snapshot);
                            project.getLogger()
                                    .lifecycle(
                                            "Spotless Daemon pid={}",
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
        project.getTasks().register("spotlessDaemonApply", task -> {
            task.setGroup("formatting");
            task.setDescription("Format only paths in the UTF-8 file given by -PspotlessDaemonFiles");
            task.notCompatibleWithConfigurationCache("Reads current project daemon metadata");
            task.dependsOn("spotlessDaemonStart");
            task.doLast(ignored -> {
                Object list = project.findProperty("spotlessDaemonFiles");
                if (list == null)
                    throw new GradleException("Supply -PspotlessDaemonFiles=<UTF-8 path list, one file per line>");
                try {
                    SharedDaemon.apply(
                            project.getProjectDir().toPath(), project.file(list).toPath());
                } catch (Exception e) {
                    throw new GradleException("Spotless Daemon apply failed", e);
                }
            });
        });
        for (String action : new String[] {"Status", "Stop"}) {
            project.getTasks().register("spotlessDaemon" + action, task -> {
                task.setGroup("formatting");
                task.notCompatibleWithConfigurationCache("Uses project daemon state");
                task.doLast(ignored -> {
                    try {
                        Path root = project.getProjectDir().toPath();
                        if (action.equals("Stop")) SharedDaemon.stop(root);
                        else project.getLogger().lifecycle("Spotless Daemon pid={}", SharedDaemon.status(root));
                    } catch (Exception e) {
                        throw new GradleException("Daemon " + action + " failed", e);
                    }
                });
            });
        }
    }
}
