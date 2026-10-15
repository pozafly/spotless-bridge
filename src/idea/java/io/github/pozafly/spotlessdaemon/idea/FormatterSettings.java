package io.github.pozafly.spotlessdaemon.idea;

import com.intellij.openapi.components.*;
import org.jetbrains.annotations.NotNull;

@Service(Service.Level.PROJECT)
@State(name = "SpotlessDaemonSettings", storages = @Storage(StoragePathMacros.WORKSPACE_FILE))
public final class FormatterSettings implements PersistentStateComponent<FormatterSettings.Values> {
    public static final class Values {
        public boolean enabled;
        public String javaHome = "";
        public String gradleRoot = "";
        public String extraWatch = "";
    }

    private Values values = new Values();

    @Override
    public Values getState() {
        return values;
    }

    @Override
    public void loadState(@NotNull Values state) {
        values = state;
    }
}
