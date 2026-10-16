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
        enabled = new JCheckBox("이 프로젝트에서 Spotless Bridge 사용");
        javaHome = new JTextField();
        panel.add(enabled);
        panel.add(new JLabel("JDK 17 이상 홈 경로 (포매터 요구 버전 확인, 비워두면 프로젝트 SDK 사용)"));
        panel.add(javaHome);
        gradleRoot = new JTextField();
        extraWatch = new JTextArea(3, 50);
        panel.add(new JLabel("Gradle 루트 경로 (비우면 해당 Java 파일에서 가장 가까운 wrapper 탐색)"));
        panel.add(gradleRoot);
        panel.add(new JLabel("추가 변경 감지 경로 (외부 스크립트/설정, 한 줄에 하나, 상대 경로는 Gradle 루트 기준)"));
        panel.add(new JScrollPane(extraWatch));
        JButton reload = new JButton("다음 포맷 시 Spotless 설정 다시 불러오기");
        reload.addActionListener(
                event -> project.getService(ProjectEngine.class).stop());
        panel.add(reload);
        panel.add(new JLabel("저장 시 실행: Tools > Actions on Save > Reformat code (Java, Whole file)"));
        panel.add(new JLabel("기존 Formo, Java Save Formatter, Run spotless, 다른 Java 포매터는 끈 후 사용하세요."));
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
