package io.github.pozafly.spotlessbridge.idea;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ProjectRootManager;
import io.github.pozafly.spotlessbridge.GradleFormatter;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

@Service(Service.Level.PROJECT)
public final class ProjectEngine implements Disposable {
    private final Project project;
    private final Map<Path, GradleFormatter> builds = new HashMap<>();
    private boolean disposed;

    public ProjectEngine(Project project) {
        this.project = project;
    }

    private synchronized GradleFormatter formatter(Path source) throws IOException {
        if (disposed || project.isDisposed()) throw new IOException("Project is closed");
        FormatterSettings.Values settings =
                project.getService(FormatterSettings.class).getState();
        if (!settings.enabled) throw new IOException("Spotless Bridge is disabled");
        Path root;
        if (!settings.gradleRoot.isBlank()) root = Path.of(settings.gradleRoot).toRealPath();
        else {
            root = source.toAbsolutePath().getParent();
            while (root != null && !Files.isRegularFile(root.resolve("gradle/wrapper/gradle-wrapper.jar")))
                root = root.getParent();
            if (root == null)
                throw new IOException("Gradle wrapper를 찾을 수 없습니다. Spotless Bridge 설정에서 Gradle 루트를 지정하세요.");
            root = root.toRealPath();
        }
        GradleFormatter existing = builds.get(root);
        if (existing != null) return existing;
        String home = settings.javaHome;
        if (home.isBlank())
            home = ReadAction.compute(() -> {
                Sdk sdk = ProjectRootManager.getInstance(project).getProjectSdk();
                return sdk == null ? null : sdk.getHomePath();
            });
        if (home == null || home.isBlank()) throw new IOException("Spotless Bridge 설정에서 JDK 21 경로를 지정하세요.");
        List<Path> extraWatch = new ArrayList<>();
        for (String line : settings.extraWatch.lines().toList())
            if (!line.isBlank()) extraWatch.add(root.resolve(line.trim()).normalize());
        GradleFormatter created = new GradleFormatter(
                root,
                Path.of(home),
                extraWatch,
                () -> NotificationGroupManager.getInstance()
                        .getNotificationGroup("Spotless Bridge")
                        .createNotification(
                                "Spotless Bridge 설정 불러오는 중", "빌드 설정을 평가한 뒤 새 규칙으로 포맷합니다.", NotificationType.INFORMATION)
                        .notify(project));
        created.refresh();
        builds.put(root, created);
        return created;
    }

    public String format(Path file, String source) throws IOException {
        return formatter(file).format(file, source).replace("\r\n", "\n").replace('\r', '\n');
    }

    public synchronized void stop() {
        builds.values().forEach(GradleFormatter::close);
        builds.clear();
    }

    @Override
    public synchronized void dispose() {
        disposed = true;
        stop();
    }
}
