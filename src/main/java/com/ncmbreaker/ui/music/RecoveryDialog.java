package com.ncmbreaker.ui.music;

import com.ncmbreaker.download.DownloadManager;
import com.ncmbreaker.download.DownloadManager.*;
import com.ncmbreaker.ui.UiStyle;
import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

final class RecoveryDialog extends JDialog {
    private record Group(String name, List<Task> tasks) { }
    RecoveryDialog(Window owner, DownloadManager manager) {
        super(owner, "继续下载任务", ModalityType.APPLICATION_MODAL);
        var groups = new LinkedHashMap<String, List<Task>>();
        for (var batch : manager.batches()) groups.put(batch.id, List.copyOf(batch.tasks));
        for (var task : manager.tasks()) if (task.batch == null) groups.put(task.id, List.of(task));
        var rows = groups.values().stream().filter(tasks -> tasks.stream().anyMatch(task -> task.state == State.PAUSED))
                .map(tasks -> new Group(tasks.get(0).batch == null ? tasks.get(0).song.title() : tasks.get(0).sourceName(), tasks)).toList();
        var model = new AbstractTableModel() {
            private final String[] columns = {"来源", "音质", "目录", "已完成", "待继续"};
            @Override public int getRowCount() { return rows.size(); }
            @Override public int getColumnCount() { return columns.length; }
            @Override public String getColumnName(int column) { return columns[column]; }
            @Override public Object getValueAt(int row, int column) {
                var group = rows.get(row);
                return switch (column) {
                    case 0 -> group.name();
                    case 1 -> group.tasks().stream().map(task -> task.quality.toString()).distinct().collect(java.util.stream.Collectors.joining("、"));
                    case 2 -> group.tasks().get(0).directory.toString();
                    case 3 -> group.tasks().stream().filter(task -> task.state == State.SUCCEEDED || task.state == State.SKIPPED).count();
                    default -> group.tasks().stream().filter(task -> task.state == State.PAUSED).count();
                };
            }
        };
        var root = new JPanel(new BorderLayout(0, 12)); root.setBorder(BorderFactory.createEmptyBorder(18, 20, 16, 20));
        var table = new JTable(model); UiStyle.table(table, 44); table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setName("recovery.table"); table.getColumnModel().getColumn(2).setPreferredWidth(280);
        root.add(UiStyle.scrollPane(table), BorderLayout.CENTER);
        var details = new JTextArea(4, 40); details.setEditable(false); details.setLineWrap(true); details.setWrapStyleWord(true);
        details.setFont(UIManager.getFont("Label.font")); details.setOpaque(false);
        table.getSelectionModel().addListSelectionListener(event -> {
            int index = table.getSelectedRow();
            if (index < 0) return;
            var task = rows.get(index).tasks().get(0); var options = task.options;
            details.setText("来源：" + task.sourceName() + "\n保存目录：" + task.directory + "\n文件组织：" + options.layout()
                    + "；标签：" + (options.tags() ? "写入" : "不写入") + "；封面：" + (options.cover() ? "嵌入" : "不嵌入")
                    + "；歌词：" + (options.saveLyrics() ? "保存 LRC" : "不保存 LRC") + (options.embedLyrics() ? "、嵌入" : "")
                    + (task.session == null ? "\n此任务需要登录原账号后继续" : ""));
        });
        var footer = new JPanel(new BorderLayout(0, 10)); footer.add(details, BorderLayout.CENTER);
        var actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        var close = UiStyle.button("关闭"); close.addActionListener(event -> dispose());
        var resume = UiStyle.button("继续所选批次"); resume.setName("recovery.resume");
        resume.setEnabled(false);
        table.getSelectionModel().addListSelectionListener(event -> resume.setEnabled(java.util.Arrays.stream(table.getSelectedRows())
                .mapToObj(rows::get).flatMap(group -> group.tasks().stream())
                .anyMatch(task -> task.state == State.PAUSED && task.session != null)));
        resume.addActionListener(event -> {
            var selected = java.util.Arrays.stream(table.getSelectedRows()).mapToObj(rows::get).flatMap(group -> group.tasks().stream()).toList();
            manager.resume(selected); dispose();
        });
        actions.add(close); actions.add(resume); footer.add(actions, BorderLayout.SOUTH); root.add(footer, BorderLayout.SOUTH);
        if (!rows.isEmpty()) table.setRowSelectionInterval(0, rows.size() - 1);
        setContentPane(root); setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(640, 390)); setSize(880, 510); setLocationRelativeTo(owner);
    }
}
