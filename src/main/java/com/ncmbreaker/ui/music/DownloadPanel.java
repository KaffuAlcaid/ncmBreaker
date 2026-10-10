package com.ncmbreaker.ui.music;

import com.ncmbreaker.netease.music.MusicModels.Song;
import com.ncmbreaker.ui.UiStyle;
import javax.swing.*;
import javax.swing.event.TableModelEvent;
import java.awt.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class DownloadPanel extends JPanel {
    private final DownloadQueue queue;
    private final JTable table;
    private final JButton cancel = UiStyle.button("取消所选");
    private final JButton retry = UiStyle.button("重新选择音质");
    private final JButton clear = UiStyle.button("清除已结束");
    private final JLabel summary = new JLabel("暂无下载任务");
    private final JLabel status = new JLabel(" ");
    private final Supplier<Path> defaultDirectory;
    private boolean signedIn;
    private boolean closed;

    DownloadPanel(DownloadQueue queue, Consumer<Song> retryDownload, Supplier<Path> defaultDirectory) {
        super(new BorderLayout(0, 18));
        this.queue = queue;
        this.defaultDirectory = defaultDirectory;
        table = new JTable(queue);
        setBorder(BorderFactory.createEmptyBorder(24, 24, 18, 24));
        var heading = new JPanel(new BorderLayout(0, 6));
        var title = new JLabel("下载任务");
        title.setFont(title.getFont().deriveFont(22f));
        heading.add(title, BorderLayout.NORTH);
        summary.setForeground(UiStyle.muted());
        heading.add(summary, BorderLayout.SOUTH);
        add(heading, BorderLayout.NORTH);
        UiStyle.table(table, 54);
        table.setName("downloads.table");
        table.getColumnModel().getColumn(0).setPreferredWidth(250);
        table.getColumnModel().getColumn(1).setPreferredWidth(100);
        table.getColumnModel().getColumn(2).setPreferredWidth(320);
        table.getColumnModel().getColumn(3).setPreferredWidth(230);
        table.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) updateActions();
        });
        add(UiStyle.scrollPane(table), BorderLayout.CENTER);
        var footer = new JPanel(new BorderLayout(0, 10));
        var actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        var folder = UiStyle.button("打开目录");
        folder.setIcon(UIManager.getIcon("FileView.directoryIcon"));
        cancel.addActionListener(event -> {
            int row = table.getSelectedRow();
            if (row >= 0) queue.cancel(row);
        });
        retry.addActionListener(event -> {
            int row = table.getSelectedRow();
            if (row >= 0) retryDownload.accept(queue.task(row).song);
        });
        folder.addActionListener(event -> openDirectory(folder));
        clear.addActionListener(event -> queue.clearFinished());
        actions.add(cancel); actions.add(retry); actions.add(folder); actions.add(clear);
        footer.add(actions, BorderLayout.NORTH);
        status.setForeground(UiStyle.muted());
        status.putClientProperty("html.disable", Boolean.TRUE);
        status.setPreferredSize(new Dimension(0, 24));
        footer.add(status, BorderLayout.SOUTH);
        add(footer, BorderLayout.SOUTH);
        queue.addTableModelListener(event -> SwingUtilities.invokeLater(() -> {
            if (closed) return;
            int count = queue.getRowCount();
            if (count > 0) {
                int selected = table.getSelectedRow();
                if (event.getType() == TableModelEvent.INSERT) {
                    int row = Math.min(event.getLastRow(), count - 1);
                    table.setRowSelectionInterval(row, row);
                    table.scrollRectToVisible(table.getCellRect(row, 0, true));
                } else if (selected < 0 || selected >= count) {
                    table.setRowSelectionInterval(0, 0);
                }
            }
            updateActions();
        }));
        updateActions();
    }

    void setSignedIn(boolean value) { signedIn = value; updateActions(); }
    void close() { closed = true; }

    private void updateActions() {
        int row = table.getSelectedRow();
        var task = row >= 0 && row < queue.getRowCount() ? queue.task(row) : null;
        cancel.setEnabled(task != null && !task.finished);
        retry.setEnabled(task != null && task.finished && signedIn);
        int finished = 0;
        for (int index = 0; index < queue.getRowCount(); index++) if (queue.task(index).finished) finished++;
        clear.setEnabled(finished > 0);
        int total = queue.getRowCount();
        summary.setText(total == 0 ? "暂无下载任务"
                : total + " 个任务 · " + (total - finished) + " 个进行中 · " + finished + " 个已结束");
        showStatus(task == null ? " " : task.output == null ? task.directory.toString() : task.output.toString());
    }

    private void openDirectory(JButton button) {
        final Path directory;
        try {
            int row = table.getSelectedRow();
            directory = row >= 0 && row < queue.getRowCount()
                    ? queue.task(row).directory : defaultDirectory.get();
        } catch (IllegalArgumentException exception) { showStatus("请选择有效的保存目录"); return; }
        button.setEnabled(false);
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception {
                Files.createDirectories(directory);
                Desktop.getDesktop().open(directory.toFile());
                return null;
            }
            @Override protected void done() {
                button.setEnabled(true);
                if (closed) return;
                try { get(); showStatus(directory.toString()); }
                catch (Exception exception) { showStatus("无法打开目录：" + directory); }
            }
        }.execute();
    }

    private void showStatus(String text) {
        status.setText(text);
        status.setToolTipText(text.isBlank() ? null : text);
    }
}
