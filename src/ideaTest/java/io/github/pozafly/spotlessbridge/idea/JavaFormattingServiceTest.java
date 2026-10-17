package io.github.pozafly.spotlessbridge.idea;

import com.intellij.formatting.FormattingContext;
import com.intellij.formatting.service.AsyncDocumentFormattingService;
import com.intellij.formatting.service.AsyncFormattingRequest;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import java.nio.file.Path;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class JavaFormattingServiceTest {
    private final Application application = mock(Application.class);
    private final Project project = mock(Project.class);
    private final Document document = mock(Document.class);
    private final ProjectEngine engine = mock(ProjectEngine.class);
    private final AsyncFormattingRequest request = mock(AsyncFormattingRequest.class);
    private final FormatterSettings settings = new FormatterSettings();
    private final Path file = Path.of("Example.java");
    private MockedStatic<ApplicationManager> applications;
    private boolean reading;

    @Before
    public void setUp() throws Exception {
        applications = mockStatic(ApplicationManager.class);
        applications.when(ApplicationManager::getApplication).thenReturn(application);
        when(application.runReadAction(org.mockito.ArgumentMatchers.<Computable<Document>>any()))
                .thenAnswer(call -> {
                    reading = true;
                    try {
                        return call.<Computable<Document>>getArgument(0).compute();
                    } finally {
                        reading = false;
                    }
                });
        doAnswer(call -> {
            reading = true;
            try {
                call.<Runnable>getArgument(0).run();
            } finally {
                reading = false;
            }
            return null;
        }).when(application).runReadAction(any(Runnable.class));

        FormattingContext context = mock(FormattingContext.class);
        PsiFile psiFile = mock(PsiFile.class);
        VirtualFile virtualFile = mock(VirtualFile.class);
        PsiDocumentManager documents = mock(PsiDocumentManager.class);
        when(project.getService(PsiDocumentManager.class)).thenReturn(documents);
        when(documents.getDocument(psiFile)).thenAnswer(call -> {
            assertTrue("PSI lookup must stay inside a read action", reading);
            return document;
        });
        when(request.getContext()).thenReturn(context);
        when(context.getProject()).thenReturn(project);
        when(context.getContainingFile()).thenReturn(psiFile);
        when(context.getVirtualFile()).thenReturn(virtualFile);
        when(virtualFile.getPath()).thenReturn(file.toString());
        when(request.getDocumentText()).thenReturn("source");
        when(document.getModificationStamp()).thenReturn(1L);
        when(document.getImmutableCharSequence()).thenAnswer(call -> {
            assertTrue("Document validation must stay inside a read action", reading);
            return "source";
        });
        settings.getState().enabled = true;
        when(project.getService(FormatterSettings.class)).thenReturn(settings);
        when(project.getService(ProjectEngine.class)).thenReturn(engine);
        when(engine.format(file, "source")).thenAnswer(call -> {
            assertFalse("Gradle formatting must not hold the IDE read lock", reading);
            return "formatted";
        });
    }

    // FormattingTask is a protected platform type; expose only its public operations to tests.
    private abstract static class TaskAccess extends AsyncDocumentFormattingService {
        static Runnable create(AsyncFormattingRequest request) {
            return new JavaFormattingService().createFormattingTask(request);
        }

        static boolean cancel(Runnable task) {
            return ((FormattingTask) task).cancel();
        }
    }

    @After
    public void tearDown() {
        applications.close();
    }

    @Test
    public void unchangedDocumentReceivesFormattedTextInsideReadAction() {
        doAnswer(call -> {
            assertTrue("Validation and callback must share the read action", reading);
            return null;
        }).when(request).onTextReady("formatted");
        TaskAccess.create(request).run();
        verify(request).onTextReady("formatted");
    }

    @Test
    public void changedDocumentDoesNotReceiveStaleFormatting() throws Exception {
        Runnable task = TaskAccess.create(request);
        when(engine.format(file, "source")).thenAnswer(call -> {
            when(document.getModificationStamp()).thenReturn(2L);
            return "formatted";
        });
        task.run();
        verify(request).onTextReady(null);
        verify(request, never()).onTextReady("formatted");
    }

    @Test
    public void cancellationDuringFormattingDiscardsResult() throws Exception {
        Runnable task = TaskAccess.create(request);
        when(engine.format(file, "source")).thenAnswer(call -> {
            assertTrue(TaskAccess.cancel(task));
            return "formatted";
        });
        task.run();
        verify(request).onTextReady(null);
        verify(request, never()).onTextReady("formatted");
    }

    @Test
    public void disablingPluginDuringFormattingDiscardsResult() throws Exception {
        Runnable task = TaskAccess.create(request);
        when(engine.format(file, "source")).thenAnswer(call -> {
            settings.getState().enabled = false;
            return "formatted";
        });
        task.run();
        verify(request).onTextReady(null);
        verify(request, never()).onTextReady("formatted");
    }
}
