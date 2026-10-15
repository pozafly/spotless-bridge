package io.github.pozafly.spotlessbridge.idea;

import com.intellij.formatting.service.*;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public final class JavaFormattingService extends AsyncDocumentFormattingService {
    @Override
    public Set<Feature> getFeatures() {
        return Set.of();
    }

    @Override
    public boolean canFormat(PsiFile file) {
        VirtualFile virtual = file.getVirtualFile();
        return !file.getProject().isDisposed()
                && file.getProject().getService(FormatterSettings.class).getState().enabled
                && "JAVA".equals(file.getLanguage().getID())
                && virtual != null
                && virtual.isInLocalFileSystem()
                && virtual.isWritable()
                && ProjectFileIndex.getInstance(file.getProject()).isInSourceContent(virtual);
    }

    @Override
    protected String getName() {
        return "Spotless Bridge";
    }

    @Override
    protected String getNotificationGroupId() {
        return "Spotless Bridge";
    }

    @Override
    protected Duration getTimeout() {
        return Duration.ofSeconds(150);
    }

    @Override
    protected FormattingTask createFormattingTask(AsyncFormattingRequest request) {
        // Import rewrites require a whole-file, non-whitespace-only formatting request.
        if (request.canChangeWhitespaceOnly() || request.isQuickFormat()) return null;
        Project project = request.getContext().getProject();
        Document document = ReadAction.compute(() -> PsiDocumentManager.getInstance(project)
                .getDocument(request.getContext().getContainingFile()));
        if (document == null) return null;
        long stamp = document.getModificationStamp();
        String source = request.getDocumentText();
        Path sourceFile = Path.of(request.getContext().getVirtualFile().getPath());
        AtomicBoolean cancelled = new AtomicBoolean();
        return new FormattingTask() {
            @Override
            public void run() {
                if (cancelled.get() || project.isDisposed()) {
                    request.onTextReady(null);
                    return;
                }
                try {
                    String formatted = project.getService(ProjectEngine.class).format(sourceFile, source);
                    ReadAction.run(() -> {
                        boolean current = !cancelled.get()
                                && !project.isDisposed()
                                && project.getService(FormatterSettings.class).getState().enabled
                                && document.getModificationStamp() == stamp
                                && source.contentEquals(document.getImmutableCharSequence());
                        // The platform also checks document changes when it applies this callback.
                        request.onTextReady(current ? formatted : null);
                    });
                } catch (ProcessCanceledException cancelledByIde) {
                    request.onTextReady(null);
                    throw cancelledByIde;
                } catch (Exception failure) {
                    if (cancelled.get()
                            || project.isDisposed()
                            || !project.getService(FormatterSettings.class).getState().enabled)
                        request.onTextReady(null);
                    else
                        request.onError(getName(), failure.getMessage() == null ? "포맷에 실패했습니다." : failure.getMessage());
                }
            }

            @Override
            public boolean cancel() {
                cancelled.set(true);
                return true;
            }
        };
    }
}
