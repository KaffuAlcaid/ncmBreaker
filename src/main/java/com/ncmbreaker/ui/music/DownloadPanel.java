package com.ncmbreaker.ui.music;

import com.ncmbreaker.download.DownloadManager;
import com.ncmbreaker.download.DownloadManager.*;
import com.ncmbreaker.ui.UiStyle;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class DownloadPanel extends JPanel {
    private final DownloadQueue queue;
    private final JTable table;
    private final TableRowSorter<DownloadQueue> sorter;
    private final JComboBox<String> stateFilter = new JComboBox<>(new String[]{"全部状态", "进行中", "等待中", "已暂停", "失败", "已完成", "已跳过", "已取消", "有提示"});
    private final JTextField search = UiStyle.searchField("搜索歌曲、歌手、来源或结果");
    private final JButton cancel = UiStyle.button("取消所选");
    private final JButton retry = UiStyle.button("重试所选");
    private final JButton resume = UiStyle.button("继续所选");
    private final JButton pause = UiStyle.button("暂停队列");
    private final JButton more = UiStyle.button("更多");
    private final JButton recovery = UiStyle.button("继续上次任务");
    private final JLabel summary = new JLabel("暂无下载任务");
    private final JLabel status = new JLabel(" ");
    private final JLabel warning = new JLabel(" ");
    private final Supplier<Path> defaultDirectory;
    private final Consumer<Task> chooseQuality;
    private boolean signedIn;
    private boolean closed;

    DownloadPanel(DownloadQueue queue, Consumer<Task> chooseQuality, Supplier<Path> defaultDirectory) {
        super(new BorderLayout(0, 14));
        this.queue = queue; this.chooseQuality = chooseQuality; this.defaultDirectory = defaultDirectory;
        setBorder(BorderFactory.createEmptyBorder(20, 24, 16, 24));
        table = new JTable(queue);
        sorter = new TableRowSorter<>(queue);
        for (int i = 0; i < queue.getColumnCount(); i++) sorter.setSortable(i, false);
        table.setRowSorter(sorter);
        var top = new JPanel(new BorderLayout(0, 12));
        var heading = new JPanel(new BorderLayout(12, 6));
        var title = new JLabel("下载任务"); title.setFont(title.getFont().deriveFont(22f));
        heading.add(title, BorderLayout.WEST);
        var scheduling = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        pause.setPreferredSize(new Dimension(94, 34));
        pause.addActionListener(event -> queue.manager.setPaused(!queue.manager.paused()));
        scheduling.add(pause); scheduling.add(new JLabel("同时下载"));
        var concurrency = new JSpinner(new SpinnerNumberModel(queue.manager.concurrency(), 1, 6, 1));
        concurrency.setName("downloads.concurrency"); concurrency.setPreferredSize(new Dimension(64, 34));
        concurrency.addChangeListener(event -> queue.manager.setConcurrency((Integer) concurrency.getValue()));
        scheduling.add(concurrency); heading.add(scheduling, BorderLayout.EAST);
        summary.setForeground(UiStyle.muted()); heading.add(summary, BorderLayout.SOUTH);
        top.add(heading, BorderLayout.NORTH);
        var filters = new JPanel(new BorderLayout(12, 0));
        UiStyle.comboBox(stateFilter); stateFilter.setPreferredSize(new Dimension(126, 36));
        stateFilter.setName("downloads.filter"); stateFilter.addActionListener(event -> filter());
        filters.add(stateFilter, BorderLayout.WEST);
        search.setName("downloads.search"); search.setPreferredSize(new Dimension(0, 36));
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { filter(); }
            @Override public void removeUpdate(DocumentEvent event) { filter(); }
            @Override public void changedUpdate(DocumentEvent event) { filter(); }
        });
        filters.add(search, BorderLayout.CENTER);
        recovery.setName("downloads.recovery");
        recovery.addActionListener(event -> new RecoveryDialog(SwingUtilities.getWindowAncestor(this), queue.manager).setVisible(true));
        filters.add(recovery, BorderLayout.EAST); top.add(filters, BorderLayout.CENTER);
        warning.setForeground(UIManager.getColor("Label.foreground")); warning.putClientProperty("html.disable", true);
        warning.setPreferredSize(new Dimension(0, 22)); top.add(warning, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);
        UiStyle.table(table, 48); table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setName("downloads.table");
        int[] widths = {220, 86, 150, 250, 240};
        for (int i = 0; i < widths.length; i++) table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        table.getSelectionModel().addListSelectionListener(event -> { if (!event.getValueIsAdjusting()) updateActions(); });
        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) { if (event.getClickCount() == 2) details(); }
        });
        add(UiStyle.scrollPane(table), BorderLayout.CENTER);
        var footer = new JPanel(new BorderLayout(0, 10));
        var actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        resume.setName("downloads.resume"); retry.setName("downloads.retry");
        resume.addActionListener(event -> queue.manager.resume(selected()));
        retry.addActionListener(event -> queue.manager.retry(selected()));
        cancel.addActionListener(event -> queue.manager.cancel(selected()));
        var folder = UiStyle.button(""); folder.setIcon(UIManager.getIcon("FileView.directoryIcon"));
        folder.setToolTipText("打开所选文件所在目录"); folder.addActionListener(event -> openDirectory());
        more.addActionListener(event -> showMore());
        actions.add(resume); actions.add(retry); actions.add(cancel); actions.add(folder); actions.add(more);
        footer.add(actions, BorderLayout.NORTH);
        status.setForeground(UiStyle.muted()); status.putClientProperty("html.disable", true);
        status.setPreferredSize(new Dimension(0, 22)); footer.add(status, BorderLayout.SOUTH);
        add(footer, BorderLayout.SOUTH);
        queue.addTableModelListener(event -> SwingUtilities.invokeLater(() -> {
            if (closed) return;
            sorter.sort(); updateActions();
        }));
        updateActions();
    }

    void setSignedIn(boolean value) { signedIn = value; updateActions(); }
    void close() { closed = true; }

    private List<Task> selected() {
        return java.util.Arrays.stream(table.getSelectedRows()).filter(row -> row < table.getRowCount())
                .map(table::convertRowIndexToModel).filter(row -> row < queue.getRowCount()).mapToObj(queue::task).toList();
    }
    private List<Task> selectedBatches() {
        var selection = selected();
        var batches = selection.stream().map(task -> task.batch).filter(java.util.Objects::nonNull).toList();
        return queue.tasks().stream().filter(task -> batches.contains(task.batch)).toList();
    }
    private void filter() {
        String query = search.getText().strip().toLowerCase(Locale.ROOT);
        int state = stateFilter.getSelectedIndex();
        sorter.setRowFilter(new RowFilter<>() {
            @Override public boolean include(Entry<? extends DownloadQueue, ? extends Integer> entry) {
                var task = queue.task(entry.getIdentifier());
                boolean matches = switch (state) {
                    case 1 -> task.started;
                    case 2 -> task.state == State.WAITING || task.state == State.RETRY_WAIT;
                    case 3 -> task.state == State.PAUSED;
                    case 4 -> task.state == State.FAILED;
                    case 5 -> task.state == State.SUCCEEDED;
                    case 6 -> task.state == State.SKIPPED;
                    case 7 -> task.state == State.CANCELLED;
                    case 8 -> task.result != null && !task.result.warnings().isEmpty();
                    default -> true;
                };
                return matches && (query.isBlank() || (task.song.title() + " " + task.song.artistText() + " "
                        + task.song.album() + " " + task.sourceName() + " " + task.status + " "
                        + (task.output == null ? task.directory : task.output)).toLowerCase(Locale.ROOT).contains(query));
            }
        });
        updateActions();
    }

    private void updateActions() {
        var selection = selected();
        resume.setEnabled(signedIn && selection.stream().anyMatch(task -> task.state == State.PAUSED && task.session != null));
        retry.setEnabled(signedIn && selection.stream().anyMatch(task -> task.session != null
                && (task.state == State.FAILED || task.state == State.CANCELLED)));
        cancel.setEnabled(selection.stream().anyMatch(task -> !task.finished()));
        long running = queue.tasks().stream().filter(task -> task.started).count();
        long failed = queue.tasks().stream().filter(task -> task.state == State.FAILED).count();
        long completed = queue.tasks().stream().filter(task -> task.state == State.SUCCEEDED).count();
        long waiting = queue.tasks().stream().filter(task -> task.state == State.WAITING || task.state == State.RETRY_WAIT).count();
        long paused = queue.tasks().stream().filter(task -> task.state == State.PAUSED).count();
        var counts = new StringBuilder(queue.getRowCount() + " 个任务");
        long[] values = {running, waiting, paused, completed, failed,
                queue.tasks().stream().filter(task -> task.state == State.SKIPPED).count(),
                queue.tasks().stream().filter(task -> task.state == State.CANCELLED).count()};
        String[] labels = {"进行中", "等待", "暂停", "完成", "失败", "跳过", "取消"};
        for (int i = 0; i < values.length; i++) if (values[i] > 0) counts.append("  ·  ").append(values[i]).append(" 个").append(labels[i]);
        summary.setText(counts.toString());
        summary.setToolTipText(counts.toString());
        recovery.setEnabled(signedIn && paused > 0);
        pause.setText(queue.manager.paused() ? "继续队列" : "暂停队列");
        pause.setToolTipText(queue.manager.paused() ? "继续启动等待中的任务" : "暂停启动新任务");
        warning.setText(!queue.manager.warning().isBlank() ? queue.manager.warning()
                : queue.manager.paused() ? "队列已暂停，正在下载的歌曲将继续完成" : " ");
        warning.setToolTipText(warning.getText().isBlank() ? null : warning.getText());
        showStatus(selection.size() == 1 ? selection.get(0).output == null ? selection.get(0).directory.toString()
                : selection.get(0).output.toString() : selection.isEmpty() ? "当前显示 " + table.getRowCount() + " 个任务" : "已选择 " + selection.size() + " 个任务");
    }

    private void showMore() {
        var menu = new JPopupMenu();
        var selection = selected();
        boolean waiting = selection.stream().anyMatch(task -> task.state == State.WAITING || task.state == State.RETRY_WAIT);
        add(menu, "暂停所选待下载任务", waiting, () -> queue.manager.pause(selected()));
        add(menu, "优先下载所选", !selection.isEmpty(), () -> queue.manager.prioritize(selected(), true));
        add(menu, "移至等待队列末尾", !selection.isEmpty(), () -> queue.manager.prioritize(selected(), false));
        menu.addSeparator();
        boolean batch = selection.stream().anyMatch(task -> task.batch != null);
        add(menu, "暂停本批待下载任务", batch, () -> queue.manager.pause(selectedBatches()));
        add(menu, "继续本批任务", signedIn && batch, () -> queue.manager.resume(selectedBatches()));
        add(menu, "重试本批失败任务", signedIn && batch, () -> queue.manager.retry(selectedBatches().stream().filter(task -> task.state == State.FAILED).toList()));
        add(menu, "取消整批", batch, () -> queue.manager.cancel(selectedBatches()));
        add(menu, "重新选择音质", signedIn && selection.size() == 1 && selection.get(0).finished(), () -> chooseQuality.accept(selected().get(0)));
        menu.addSeparator();
        add(menu, "查看结果详情", selection.size() == 1, this::details);
        add(menu, "复制所选结果", !selection.isEmpty(), () -> copy(false));
        add(menu, "复制所选文件路径", selection.stream().anyMatch(task -> task.output != null), () -> copy(true));
        add(menu, "导出当前结果", table.getRowCount() > 0, this::export);
        add(menu, "清除已结束任务", queue.tasks().stream().anyMatch(Task::finished), queue::clearFinished);
        menu.show(more, 0, more.getHeight());
    }
    private static void add(JPopupMenu menu, String text, boolean enabled, Runnable action) {
        var item = new JMenuItem(text); item.setEnabled(enabled); item.addActionListener(event -> action.run()); menu.add(item);
    }
    private void details() {
        var selection = selected(); if (selection.size() != 1) return;
        var task = selection.get(0);
        String message = task.song.title() + "\n" + task.song.artistText() + "\n来源：" + task.sourceName()
                + "\n音质：" + task.quality + "\n状态：" + task.status + "\n尝试次数：" + task.attempts
                + "\n文件：" + (task.output == null ? task.directory : task.output)
                + (task.result == null ? "" : "\n" + task.result.warningText()
                + task.result.additionalFiles().stream().map(path -> "\n" + path).collect(java.util.stream.Collectors.joining()));
        var area = new JTextArea(message, 14, 52); area.setEditable(false); area.setLineWrap(true); area.setWrapStyleWord(true);
        area.setCaretPosition(0); area.setFont(UIManager.getFont("Label.font"));
        JOptionPane.showMessageDialog(this, UiStyle.scrollPane(area), "下载结果", JOptionPane.PLAIN_MESSAGE);
    }
    private void copy(boolean pathsOnly) {
        var text = selected().stream().filter(task -> !pathsOnly || task.output != null)
                .map(task -> pathsOnly ? task.output.toString() : task.song.title() + "\t" + task.song.artistText()
                        + "\t" + task.quality + "\t" + task.status + "\t" + (task.output == null ? "" : task.output))
                .collect(java.util.stream.Collectors.joining("\n"));
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
        showStatus("已复制");
    }
    private void export() {
        var chooser = new JFileChooser(defaultDirectory.get().toFile());
        chooser.setDialogTitle("导出下载结果"); chooser.setSelectedFile(new java.io.File("下载结果.csv"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        var target = chooser.getSelectedFile().toPath();
        if (Files.exists(target) && JOptionPane.showConfirmDialog(this, "是否替换已有文件？\n" + target, "导出结果",
                JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        var text = new StringBuilder("\ufeff歌曲,歌手,来源,音质,状态,文件,提示\r\n");
        for (int row = 0; row < table.getRowCount(); row++) {
            var task = queue.task(table.convertRowIndexToModel(row));
            text.append(java.util.stream.Stream.of(task.song.title(), task.song.artistText(), task.sourceName(), task.quality.toString(),
                    task.status, task.output == null ? "" : task.output.toString(), task.result == null ? "" : task.result.warningText())
                    .map(DownloadPanel::csv).collect(java.util.stream.Collectors.joining(","))).append("\r\n");
        }
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception { Files.writeString(target, text, StandardCharsets.UTF_8); return null; }
            @Override protected void done() {
                try { get(); showStatus("已导出：" + target); }
                catch (Exception exception) { showStatus("导出失败：" + com.ncmbreaker.netease.music.MusicException.describe(exception)); }
            }
        }.execute();
    }
    private static String csv(String value) {
        if (!value.isEmpty() && "=+-@\t\r".indexOf(value.charAt(0)) >= 0) value = "'" + value;
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
    private void openDirectory() {
        var selection = selected();
        var path = selection.isEmpty() ? defaultDirectory.get() : selection.get(0).output == null
                ? selection.get(0).directory : selection.get(0).output.getParent();
        if (!Files.isDirectory(path)) { showStatus("目录不存在：" + path); return; }
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception { Desktop.getDesktop().open(path.toFile()); return null; }
            @Override protected void done() {
                try { get(); showStatus(path.toString()); }
                catch (Exception exception) { showStatus("无法打开目录：" + path); }
            }
        }.execute();
    }
    private void showStatus(String text) { status.setText(text); status.setToolTipText(text.isBlank() ? null : text); }
}
