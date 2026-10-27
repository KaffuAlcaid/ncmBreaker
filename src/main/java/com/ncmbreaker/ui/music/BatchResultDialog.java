package com.ncmbreaker.ui.music;

import com.ncmbreaker.download.DownloadManager;

import com.ncmbreaker.ui.UiStyle;
import javax.swing.*;
import java.awt.*;
import java.util.stream.Collectors;
import static com.ncmbreaker.download.DownloadManager.State.*;

final class BatchResultDialog extends JDialog {
    private boolean showFailures;

    BatchResultDialog(Window owner, DownloadManager.BatchResult result) {
        super(owner, "批量下载结果", ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        var root = new JPanel(new BorderLayout(0, 16));
        root.setBorder(BorderFactory.createEmptyBorder(20, 22, 18, 22));
        var heading = new JPanel(new BorderLayout(0, 12));
        var title = text(result.source().name());
        title.setFont(title.getFont().deriveFont(16f));
        heading.add(title, BorderLayout.NORTH);
        long failed = result.count(FAILED);
        var summary = new StringBuilder("完成 " + result.count(SUCCEEDED) + " 首，失败 " + failed + " 首");
        long cancelled = result.count(CANCELLED);
        if (cancelled > 0) summary.append("，取消 ").append(cancelled).append(" 首");
        if (result.count(SKIPPED) > 0) summary.append("，跳过已有文件 ").append(result.count(SKIPPED)).append(" 首");
        if (result.reused() > 0) summary.append("，使用已有文件 ").append(result.reused()).append(" 首");
        if (result.playlistFile() != null) summary.append("\nM3U8：").append(result.playlistFile());
        if (!result.playlistWarning().isBlank()) summary.append("\n").append(result.playlistWarning());
        long warnings = result.tasks().stream().filter(task -> task.output() != null && !task.output().warnings().isEmpty()).count();
        if (warnings > 0) summary.append("\n").append(warnings).append(" 首存在附加内容提示，可在下载任务中查看");
        if (result.skipped() > 0) summary.append("\n另有 ").append(result.skipped()).append(" 首已在下载队列，未重复加入");
        int unread = result.source().expectedCount() - result.source().songs().size();
        if (unread > 0) summary.append("\n原")
                .append(result.source().playlist() == null ? "歌单" : result.source().playlist().type())
                .append("还有 ").append(unread).append(" 首未读取");
        heading.add(text(summary.toString()), BorderLayout.CENTER);
        root.add(heading, BorderLayout.NORTH);
        if (failed > 0) {
            var reasons = result.tasks().stream().filter(task -> task.state() == FAILED).map(task -> {
                var song = task.song();
                var artist = song.artistText().isBlank() ? "未知歌手" : song.artistText();
                return artist + "-" + song.title() + ": " + task.message();
            }).collect(Collectors.joining("\n\n"));
            var details = text(reasons);
            details.setFocusable(true);
            details.setCaretPosition(0);
            var scroll = UiStyle.scrollPane(details);
            scroll.setPreferredSize(new Dimension(480, 220));
            scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            scroll.setVisible(false);
            var disclosure = new JToggleButton("失败原因（" + failed + "）");
            disclosure.setName("batch.failureDetails");
            disclosure.setHorizontalAlignment(SwingConstants.LEFT);
            UiStyle.button(disclosure);
            disclosure.setIcon(UIManager.getIcon("Tree.collapsedIcon"));
            disclosure.addActionListener(event -> {
                scroll.setVisible(disclosure.isSelected());
                disclosure.setIcon(UIManager.getIcon(disclosure.isSelected() ? "Tree.expandedIcon" : "Tree.collapsedIcon"));
                pack();
                setLocationRelativeTo(owner);
            });
            var failurePanel = new JPanel(new BorderLayout(0, 10));
            failurePanel.add(disclosure, BorderLayout.NORTH);
            failurePanel.add(scroll, BorderLayout.CENTER);
            root.add(failurePanel, BorderLayout.CENTER);
        }
        var actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        var confirm = UiStyle.button("确认");
        confirm.addActionListener(event -> dispose());
        actions.add(confirm);
        if (failed > 0) {
            var failures = UiStyle.button("展示失败歌曲");
            failures.setName("batch.showFailures");
            failures.addActionListener(event -> { showFailures = true; dispose(); });
            actions.add(failures);
        }
        root.add(actions, BorderLayout.SOUTH);
        setContentPane(root);
        getRootPane().setDefaultButton(confirm);
        pack();
        setMinimumSize(new Dimension(getWidth(), getHeight()));
        setLocationRelativeTo(owner);
    }

    boolean showFailures() { return showFailures; }

    private static JTextArea text(String value) {
        var area = new JTextArea(value, 0, 40);
        area.setEditable(false); area.setFocusable(false); area.setOpaque(false);
        area.setLineWrap(true); area.setWrapStyleWord(true);
        area.setFont(UIManager.getFont("Label.font"));
        area.setForeground(UIManager.getColor("Label.foreground"));
        return area;
    }
}
