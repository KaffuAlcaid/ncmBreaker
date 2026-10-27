package com.ncmbreaker.ui.music;

import com.ncmbreaker.audio.AudioResult;
import com.ncmbreaker.download.DownloadManager.*;
import com.ncmbreaker.netease.music.MusicModels.Song;
import com.ncmbreaker.ui.UiStyle;
import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class IncrementalDownloadDialog extends JDialog {
    record Selection(List<Song> songs, Map<Long, AudioResult> existing) { }
    private Selection selection;

    IncrementalDownloadDialog(Window owner, Comparison comparison) {
        super(owner, "下载差异", ModalityType.APPLICATION_MODAL);
        var items = comparison.items();
        var checked = new boolean[items.size()];
        var summary = new JLabel();
        var model = new AbstractTableModel() {
            private final String[] columns = {"下载", "歌曲", "歌手", "本地状态", "已有音质"};
            @Override public int getRowCount() { return items.size(); }
            @Override public int getColumnCount() { return columns.length; }
            @Override public String getColumnName(int column) { return columns[column]; }
            @Override public Class<?> getColumnClass(int column) { return column == 0 ? Boolean.class : String.class; }
            @Override public boolean isCellEditable(int row, int column) { return column == 0; }
            @Override public Object getValueAt(int row, int column) {
                var item = items.get(row);
                return switch (column) {
                    case 0 -> checked[row]; case 1 -> item.song().title(); case 2 -> item.song().artistText();
                    case 3 -> item.difference().toString();
                    default -> item.file() == null ? "" : item.file().quality().toString();
                };
            }
            @Override public void setValueAt(Object value, int row, int column) {
                if (column == 0 && value instanceof Boolean selected) { checked[row] = selected; fireTableCellUpdated(row, column); }
            }
        };
        var root = new JPanel(new BorderLayout(0, 12)); root.setBorder(BorderFactory.createEmptyBorder(18, 20, 16, 20));
        var top = new JPanel(new BorderLayout(12, 10));
        var presets = new JComboBox<>(new String[]{"只补缺失文件", "补齐所选音质", "选择全部歌曲", "取消全部选择"});
        presets.setName("incremental.mode"); UiStyle.comboBox(presets); presets.setPreferredSize(new Dimension(180, 36));
        Runnable apply = () -> {
            for (int i = 0; i < items.size(); i++) {
                var difference = items.get(i).difference();
                checked[i] = switch (presets.getSelectedIndex()) {
                    case 0 -> difference == Difference.NEW || difference == Difference.MISSING;
                    case 1 -> difference != Difference.PRESENT;
                    case 2 -> true; default -> false;
                };
            }
            model.fireTableDataChanged();
        };
        presets.addActionListener(event -> apply.run()); top.add(presets, BorderLayout.WEST);
        summary.setForeground(UiStyle.muted()); top.add(summary, BorderLayout.CENTER); root.add(top, BorderLayout.NORTH);
        var table = new JTable(model); UiStyle.table(table, 44); table.setName("incremental.table");
        int[] widths = {48, 160, 120, 126, 92};
        for (int i = 0; i < widths.length; i++) {
            table.getColumnModel().getColumn(i).setMinWidth(widths[i]);
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }
        table.getColumnModel().getColumn(0).setMaxWidth(48);
        var details = new JTextArea(3, 40); details.setEditable(false); details.setLineWrap(true); details.setWrapStyleWord(true);
        details.setFont(UIManager.getFont("Label.font")); details.setOpaque(false);
        table.getSelectionModel().addListSelectionListener(event -> {
            int row = table.getSelectedRow(); if (row < 0) return;
            var item = items.get(row);
            details.setText(item.song().title() + " · " + item.song().artistText() + "\n"
                    + (item.file() == null ? "尚无下载记录" : item.file().result().file().toString()));
            details.setCaretPosition(0);
        });
        var current = new JPanel(new BorderLayout(0, 12));
        current.add(UiStyle.scrollPane(table), BorderLayout.CENTER); current.add(details, BorderLayout.SOUTH);
        if (!items.isEmpty()) table.setRowSelectionInterval(0, 0);
        if (!comparison.removed().isEmpty()) {
            var tabs = new JTabbedPane(); tabs.addTab("当前歌曲（" + items.size() + "）", current);
            var removed = new JList<>(comparison.removed().stream().map(song -> song.title() + " · " + song.artistText()).toArray(String[]::new));
            removed.setFixedCellHeight(36);
            var panel = new JPanel(new BorderLayout(0, 8));
            panel.add(new JLabel("本地文件保留"), BorderLayout.NORTH); panel.add(UiStyle.scrollPane(removed), BorderLayout.CENTER);
            tabs.addTab("已移出歌单（" + comparison.removed().size() + "）", panel);
            root.add(tabs, BorderLayout.CENTER);
        } else root.add(current, BorderLayout.CENTER);
        var actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        var cancel = UiStyle.button("返回设置"); cancel.addActionListener(event -> dispose());
        var download = UiStyle.button("下载所选"); download.setName("incremental.start");
        download.addActionListener(event -> {
            if (table.isEditing()) table.getCellEditor().stopCellEditing();
            var songs = new ArrayList<Song>(); var existing = new HashMap<Long, AudioResult>();
            for (int i = 0; i < items.size(); i++) {
                var item = items.get(i);
                if (checked[i]) songs.add(item.song());
                else if (item.difference() == Difference.PRESENT && item.file() != null) existing.put(item.song().id(), item.file().result());
            }
            selection = new Selection(List.copyOf(songs), Map.copyOf(existing)); dispose();
        });
        model.addTableModelListener(event -> {
            int count = 0; for (boolean value : checked) if (value) count++;
            long existing = items.stream().filter(item -> item.difference() == Difference.PRESENT).count();
            summary.setText(items.size() + " 首歌曲，已有所选音质 " + existing + " 首，选中 " + count + " 首");
            download.setText(count == 0 ? "使用已有文件" : "下载所选（" + count + "）");
            download.setEnabled(count > 0 || existing > 0);
        });
        apply.run(); actions.add(cancel); actions.add(download); root.add(actions, BorderLayout.SOUTH);
        setContentPane(root); setDefaultCloseOperation(DISPOSE_ON_CLOSE); getRootPane().setDefaultButton(download);
        setMinimumSize(new Dimension(640, 400)); setSize(960, 560); setLocationRelativeTo(owner);
    }
    Selection selection() { return selection; }
}
