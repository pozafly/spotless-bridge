package io.github.pozafly.spotlessbridge.idea;

import com.intellij.ide.trustedProjects.TrustedProjects;
import com.intellij.openapi.project.Project;
import io.github.pozafly.spotlessbridge.GradleFormatter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.mockito.MockedStatic;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class ProjectEngineTrustTest {
    @Test
    public void untrustedProjectStopsBeforeSettingsOrFilesystemAccess() throws Exception {
        Project project = mock(Project.class);
        try (MockedStatic<TrustedProjects> trust = mockStatic(TrustedProjects.class)) {
            trust.when(() -> TrustedProjects.isProjectTrusted(project)).thenReturn(false);
            IOException failure = assertThrows(IOException.class,
                    () -> new ProjectEngine(project).format(Path.of("does-not-exist.java"), "unchanged"));
            assertTrue(failure.getMessage().contains("Safe Mode"));
            verify(project, never()).getService(any());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void revokingTrustBlocksCachedClientAndTrustingAgainAllowsIt() throws Exception {
        Project project = mock(Project.class);
        FormatterSettings settings = new FormatterSettings();
        settings.getState().enabled = true;
        Path root = Path.of(".").toRealPath();
        settings.getState().gradleRoot = root.toString();
        when(project.getService(FormatterSettings.class)).thenReturn(settings);
        GradleFormatter client = mock(GradleFormatter.class);
        Path file = root.resolve("Example.java");
        when(client.format(file, "source")).thenReturn("formatted\r\n");
        ProjectEngine engine = new ProjectEngine(project);
        var builds = ProjectEngine.class.getDeclaredField("builds");
        builds.setAccessible(true);
        ((Map<Path, GradleFormatter>) builds.get(engine)).put(root, client);
        AtomicBoolean trusted = new AtomicBoolean(true);
        try (MockedStatic<TrustedProjects> trust = mockStatic(TrustedProjects.class)) {
            trust.when(() -> TrustedProjects.isProjectTrusted(project)).thenAnswer(call -> trusted.get());
            assertEquals("formatted\n", engine.format(file, "source"));
            trusted.set(false);
            assertThrows(IOException.class, () -> engine.format(file, "source"));
            verify(client, times(1)).format(file, "source");
            trusted.set(true);
            assertEquals("formatted\n", engine.format(file, "source"));
            verify(client, times(2)).format(file, "source");
        }
    }
}
