package io.github.pozafly.spotlessbridge.idea;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import java.awt.*;
import javax.swing.*;

public final class FormatterSettingsPage implements Configurable {
    private final Project project;
    private JCheckBox enabled;
    private JTextField javaHome;
    private JTextField gradleRoot;
    private JTextArea extraWatch;

    public FormatterSettingsPage(Project project) {
        this.project = project;
    }

    @Override
    public String getDisplayName() {
        return "Spotless Bridge";
    }

    @Override
    public JComponent createComponent() {
        JPanel panel = new JPanel(new GridLayout(0, 1, 0, 8));
        enabled = new JCheckBox("Use Spotless Bridge in this project");
        javaHome = new JTextField();
        panel.add(enabled);
        panel.add(new JLabel("JDK home, 17 or newer (check your formatter's requirement; empty uses the project SDK)"));
        panel.add(javaHome);
        gradleRoot = new JTextField();
        extraWatch = new JTextArea(3, 50);
        panel.add(new JLabel("Gradle root (empty finds the nearest wrapper from each Java file)"));
        panel.add(gradleRoot);
        panel.add(new JLabel("Extra watched paths (external scripts or settings, one per line, relative to the Gradle root)"));
        panel.add(new JScrollPane(extraWatch));
        JButton reload = new JButton("Reload Spotless configuration on next format");
        reload.addActionListener(
                event -> project.getService(ProjectEngine.class).stop());
        panel.add(reload);
        panel.add(new JLabel("Format on save: Tools > Actions on Save > Reformat code (Java, Whole file)"));
        panel.add(new JLabel("Turn off other Java formatters on save, such as Java Save Formatter or Run spotless."));
        JPanel container = new JPanel(new BorderLayout());
        container.add(panel, BorderLayout.NORTH);
        reset();
        return container;
    }

    @Override
    public boolean isModified() {
        FormatterSettings.Values settings =
                project.getService(FormatterSettings.class).getState();
        return enabled.isSelected() != settings.enabled
                || !javaHome.getText().trim().equals(settings.javaHome)
                || !gradleRoot.getText().trim().equals(settings.gradleRoot)
                || !extraWatch.getText().trim().equals(settings.extraWatch);
    }

    @Override
    public void apply() {
        FormatterSettings.Values settings =
                project.getService(FormatterSettings.class).getState();
        settings.enabled = enabled.isSelected();
        settings.javaHome = javaHome.getText().trim();
        settings.gradleRoot = gradleRoot.getText().trim();
        settings.extraWatch = extraWatch.getText().trim();
        project.getService(ProjectEngine.class).stop();
    }

    @Override
    public void reset() {
        FormatterSettings.Values settings =
                project.getService(FormatterSettings.class).getState();
        enabled.setSelected(settings.enabled);
        javaHome.setText(settings.javaHome);
        gradleRoot.setText(settings.gradleRoot);
        extraWatch.setText(settings.extraWatch);
    }

    @Override
    public void disposeUIResources() {
        enabled = null;
        javaHome = null;
        gradleRoot = null;
        extraWatch = null;
    }
}
