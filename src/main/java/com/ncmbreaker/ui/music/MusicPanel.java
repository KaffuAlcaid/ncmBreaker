package com.ncmbreaker.ui.music;

import com.ncmbreaker.netease.auth.LoginSession;
import com.ncmbreaker.netease.music.MusicModels.*;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.TableModelEvent;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public final class MusicPanel extends JPanel implements AutoCloseable {
    private final MusicController controller = new MusicController(this);
    private final MusicTableModel songs = new MusicTableModel();
    private final DownloadQueue queue = new DownloadQueue();
    private final JTable songTable = new JTable(songs);
    private final JTable taskTable = new JTable(queue);
    private final TableRowSorter<MusicTableModel> sorter = new TableRowSorter<>(songs);
    private final DefaultListModel<Playlist> playlistModel = new DefaultListModel<>();
    private final JList<Playlist> playlistList = new JList<>(playlistModel);
    private final JComboBox<Category> category = new JComboBox<>(Category.values());
    private final JTextField link = new JTextField();
    private final JTextField filter = new JTextField();
    private final JTextField output = new JTextField(Path.of(System.getProperty("user.home"), "Music", "NCM Breaker").toString());
    private final JCheckBox tags = new JCheckBox("写入歌曲标签", true);
    private final JCheckBox cover = new JCheckBox("嵌入专辑封面", true);
    private final JLabel title = new JLabel("音乐下载");
    private final JLabel account = new JLabel("尚未登录");
    private final JLabel status = new JLabel("请先登录网易云账号");
    private final JButton refresh = new JButton("↻");
    private final JButton open = new JButton("打开");
    private final JButton login = new JButton("登录账号");
    private final JButton cancelTask = new JButton("取消所选");
    private final JButton retryTask = new JButton("重新选择音质");
    private final JButton clearTasks = new JButton("清除已结束");
    private final JTabbedPane views = new JTabbedPane();
    private final List<QualityDialog> dialogs = new ArrayList<>();
    private List<Playlist> playlists = List.of();
    private boolean changingList;
    private boolean closed;
    private Runnable loginAction = () -> { };

    public MusicPanel() {
        super(new BorderLayout(0, 8));
        setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        add(toolbar(), BorderLayout.NORTH);
        var split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sidebar(), content());
        split.setBorder(BorderFactory.createEmptyBorder());
        split.setDividerLocation(235); split.setResizeWeight(0);
        add(split, BorderLayout.CENTER);
        status.setPreferredSize(new Dimension(300, 26));
        status.putClientProperty("html.disable", Boolean.TRUE);
        add(status, BorderLayout.SOUTH);
        resetAccount(null);
    }

    public void setLoginAction(Runnable action) { loginAction = action; }

    public void setSession(LoginSession session) {
        if (closed) return;
        var previous = controller.session();
        if (previous != null && previous != session) queue.cancelSession(previous);
        for (var dialog : List.copyOf(dialogs)) dialog.dispose();
        controller.setSession(session);
    }

    private JPanel toolbar() {
        var bar = new JPanel(new BorderLayout(8, 0));
        var entry = new JPanel(new BorderLayout(8, 0));
        entry.add(new JLabel("歌单链接 / ID"), BorderLayout.WEST);
        link.setToolTipText("网易云歌单链接、歌单 ID 或歌曲链接");
        entry.add(link, BorderLayout.CENTER);
        var actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        var importButton = new JButton("导入歌单", UIManager.getIcon("FileView.fileIcon"));
        importButton.addActionListener(event -> importPlaylist());
        open.addActionListener(event -> controller.open(link.getText()));
        link.addActionListener(event -> controller.open(link.getText()));
        actions.add(open); actions.add(importButton);
        entry.add(actions, BorderLayout.EAST);
        bar.add(entry, BorderLayout.CENTER);
        return bar;
    }

    private JPanel sidebar() {
        var side = new JPanel(new BorderLayout(0, 8));
        side.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 8));
        side.setMinimumSize(new Dimension(180, 160));
        var heading = new JPanel(new BorderLayout(5, 8));
        account.putClientProperty("html.disable", Boolean.TRUE);
        heading.add(account, BorderLayout.NORTH);
        heading.add(category, BorderLayout.CENTER);
        refresh.setToolTipText("刷新我的歌单"); refresh.setPreferredSize(new Dimension(36, 30));
        refresh.addActionListener(event -> controller.refresh());
        heading.add(refresh, BorderLayout.EAST);
        side.add(heading, BorderLayout.NORTH);
        playlistList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        playlistList.setFixedCellHeight(44);
        playlistList.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
                putClientProperty("html.disable", Boolean.TRUE);
                var label = (JLabel) super.getListCellRendererComponent(list, value, index, selected, focus);
                label.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
                if (value instanceof Playlist playlist) label.setToolTipText(playlist.name() + " · " + playlist.trackCount() + " 首");
                return label;
            }
        });
        playlistList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting() && !changingList && playlistList.getSelectedValue() != null) {
                controller.loadPlaylist(playlistList.getSelectedValue());
            }
        });
        category.addActionListener(event -> filterPlaylists());
        side.add(new JScrollPane(playlistList), BorderLayout.CENTER);
        login.addActionListener(event -> loginAction.run());
        side.add(login, BorderLayout.SOUTH);
        return side;
    }

    private JPanel content() {
        var panel = new JPanel(new BorderLayout(0, 8));
        var heading = new JPanel(new BorderLayout(8, 0));
        title.setFont(title.getFont().deriveFont(16f)); title.putClientProperty("html.disable", Boolean.TRUE);
        heading.add(title, BorderLayout.CENTER);
        var search = new JPanel(new BorderLayout(6, 0));
        search.add(new JLabel("筛选"), BorderLayout.WEST);
        filter.setPreferredSize(new Dimension(150, 30)); search.add(filter, BorderLayout.CENTER);
        heading.add(search, BorderLayout.EAST); panel.add(heading, BorderLayout.NORTH);
        configureSongTable(); configureTaskTable();
        views.addTab("歌曲", new JScrollPane(songTable));
        views.addTab("下载任务", taskPanel());
        queue.addTableModelListener(event -> {
            views.setTitleAt(1, "下载任务 (" + queue.getRowCount() + ")");
            SwingUtilities.invokeLater(() -> {
                if (closed) return;
                int count = queue.getRowCount();
                if (count > 0) {
                    int selected = taskTable.getSelectedRow();
                    if (event.getType() == TableModelEvent.INSERT) {
                        int row = Math.min(event.getLastRow(), count - 1);
                        taskTable.setRowSelectionInterval(row, row);
                        taskTable.scrollRectToVisible(taskTable.getCellRect(row, 0, true));
                    } else if (selected < 0 || selected >= count) {
                        taskTable.setRowSelectionInterval(0, 0);
                    }
                }
                updateTaskActions();
            });
        });
        panel.add(views, BorderLayout.CENTER);
        var destination = new JPanel(new BorderLayout(8, 0));
        destination.add(new JLabel("保存到"), BorderLayout.WEST);
        output.setToolTipText(output.getText()); destination.add(output, BorderLayout.CENTER);
        var browse = new JButton(UIManager.getIcon("FileView.directoryIcon"));
        browse.setToolTipText("选择保存目录"); browse.setPreferredSize(new Dimension(36, 30));
        browse.addActionListener(event -> selectDirectory());
        destination.add(browse, BorderLayout.EAST);
        var settings = new JPanel(new BorderLayout(0, 6));
        settings.add(destination, BorderLayout.NORTH);
        var metadata = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        metadata.add(tags); metadata.add(cover);
        settings.add(metadata, BorderLayout.SOUTH);
        panel.add(settings, BorderLayout.SOUTH);
        return panel;
    }

    private void configureSongTable() {
        songTable.setRowHeight(36); songTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        songTable.setFillsViewportHeight(true); songTable.setRowSorter(sorter);
        songTable.setDefaultRenderer(String.class, plainRenderer());
        var columns = songTable.getColumnModel();
        columns.getColumn(0).setMaxWidth(55);
        columns.getColumn(1).setPreferredWidth(240);
        columns.getColumn(2).setPreferredWidth(140);
        columns.getColumn(3).setPreferredWidth(220);
        columns.getColumn(4).setMaxWidth(65);
        columns.getColumn(5).setMaxWidth(56);
        var action = new RowAction(row -> chooseQuality(songs.song(row)));
        columns.getColumn(5).setCellRenderer(action); columns.getColumn(5).setCellEditor(action);
        sorter.setSortable(5, false);
        filter.getDocument().addDocumentListener(new DocumentListener() {
            private void update() {
                var query = filter.getText().strip().toLowerCase(java.util.Locale.ROOT);
                sorter.setRowFilter(query.isEmpty() ? null : new RowFilter<MusicTableModel, Integer>() {
                    @Override public boolean include(Entry<? extends MusicTableModel, ? extends Integer> entry) {
                        var song = songs.song(entry.getIdentifier());
                        return (song.title() + " " + song.artistText() + " " + song.album()).toLowerCase(java.util.Locale.ROOT).contains(query);
                    }
                });
            }
            @Override public void insertUpdate(DocumentEvent event) { update(); }
            @Override public void removeUpdate(DocumentEvent event) { update(); }
            @Override public void changedUpdate(DocumentEvent event) { update(); }
        });
        songTable.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke("ENTER"), "download-song");
        songTable.getActionMap().put("download-song", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) {
                if (songTable.getSelectedRow() >= 0) chooseQuality(songs.song(songTable.convertRowIndexToModel(songTable.getSelectedRow())));
            }
        });
    }

    private void configureTaskTable() {
        taskTable.setRowHeight(34); taskTable.setFillsViewportHeight(true);
        taskTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        taskTable.setDefaultRenderer(Object.class, plainRenderer());
        taskTable.getColumnModel().getColumn(0).setPreferredWidth(180);
        taskTable.getColumnModel().getColumn(1).setPreferredWidth(90);
        taskTable.getColumnModel().getColumn(2).setPreferredWidth(260);
        taskTable.getColumnModel().getColumn(3).setPreferredWidth(230);
        taskTable.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) updateTaskActions();
        });
        updateTaskActions();
    }

    private JPanel taskPanel() {
        var panel = new JPanel(new BorderLayout(0, 6));
        panel.add(new JScrollPane(taskTable), BorderLayout.CENTER);
        var actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        var folder = new JButton("打开目录", UIManager.getIcon("FileView.directoryIcon"));
        cancelTask.addActionListener(event -> { if (taskTable.getSelectedRow() >= 0) queue.cancel(taskTable.getSelectedRow()); });
        retryTask.addActionListener(event -> { if (taskTable.getSelectedRow() >= 0) chooseQuality(queue.task(taskTable.getSelectedRow()).song); });
        folder.addActionListener(event -> openDirectory(folder));
        clearTasks.addActionListener(event -> queue.clearFinished());
        actions.add(cancelTask); actions.add(retryTask); actions.add(folder); actions.add(clearTasks);
        panel.add(actions, BorderLayout.SOUTH);
        return panel;
    }

    private void updateTaskActions() {
        int row = taskTable.getSelectedRow();
        var task = row >= 0 && row < queue.getRowCount() ? queue.task(row) : null;
        cancelTask.setEnabled(task != null && !task.finished);
        retryTask.setEnabled(task != null && task.finished && controller.session() != null);
        boolean hasFinished = false;
        for (int index = 0; index < queue.getRowCount(); index++) hasFinished |= queue.task(index).finished;
        clearTasks.setEnabled(hasFinished);
    }

    private void openDirectory(JButton button) {
        final Path directory;
        try {
            int row = taskTable.getSelectedRow();
            directory = row >= 0 && row < queue.getRowCount() ? queue.task(row).directory : outputDirectory();
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
                try { get(); }
                catch (Exception exception) { showStatus("无法打开目录：" + directory); }
            }
        }.execute();
    }

    private Path outputDirectory() {
        if (output.getText().isBlank()) throw new IllegalArgumentException();
        return Path.of(output.getText().strip()).toAbsolutePath().normalize();
    }

    private static DefaultTableCellRenderer plainRenderer() {
        return new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focus, int row, int column) {
                putClientProperty("html.disable", Boolean.TRUE);
                var component = super.getTableCellRendererComponent(table, value, selected, focus, row, column);
                setToolTipText(value == null ? null : value.toString());
                return component;
            }
        };
    }

    private void chooseQuality(Song song) {
        var session = controller.session();
        if (session == null || session.account() == null) { showStatus("请先登录网易云账号"); loginAction.run(); return; }
        final Path directory;
        try {
            directory = outputDirectory();
        } catch (IllegalArgumentException exception) { showStatus("请选择有效的保存目录"); return; }
        var dialog = new QualityDialog(SwingUtilities.getWindowAncestor(this), session, song, (resolved, quality) -> {
            if (closed || controller.session() != session) return;
            boolean added = queue.add(session, resolved, quality, directory, tags.isSelected(), cover.isSelected());
            showStatus((added ? "已加入下载任务：" : "下载任务已存在：") + resolved.title() + " · " + quality);
        });
        dialogs.add(dialog);
        dialog.addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent event) { dialogs.remove(dialog); }
        });
        dialog.setVisible(true);
    }

    private void importPlaylist() {
        var chooser = new JFileChooser();
        chooser.setDialogTitle("导入歌单");
        chooser.setFileFilter(new FileNameExtensionFilter("歌单文件 (*.csv, *.json)", "csv", "json"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) controller.importFile(chooser.getSelectedFile().toPath());
    }

    private void selectDirectory() {
        var chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("选择保存目录");
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            output.setText(chooser.getSelectedFile().getAbsolutePath()); output.setToolTipText(output.getText());
        }
    }

    void resetAccount(LoginSession.Account value) {
        playlists = List.of(); changingList = true; playlistModel.clear(); changingList = false;
        if (songTable.isEditing()) songTable.getCellEditor().cancelCellEditing();
        songs.setSongs(List.of());
        title.setText("音乐下载");
        account.setText(value == null ? "尚未登录" : value.nickname()); account.setToolTipText(account.getText());
        login.setText(value == null ? "登录账号" : "管理账号");
        open.setEnabled(value != null); refresh.setEnabled(value != null); category.setEnabled(value != null);
        showStatus(value == null ? "请先登录网易云账号" : "正在读取我的歌单");
        songTable.setEnabled(true);
        updateTaskActions();
    }
    void libraryLoading(boolean loading) { refresh.setEnabled(!loading && controller.session() != null); if (loading) showStatus("正在读取我的歌单"); }
    void songsLoading(boolean loading) {
        if (songTable.isEditing()) songTable.getCellEditor().cancelCellEditing();
        songTable.setEnabled(!loading);
        if (loading) showStatus("正在读取歌单歌曲");
    }
    void showStatus(String text) { status.setText(text); status.setToolTipText(text); }
    void showPlaylists(List<Playlist> playlists) {
        this.playlists = List.copyOf(playlists);
        showStatus("已读取 " + playlists.size() + " 个歌单");
        filterPlaylists();
    }
    private void filterPlaylists() {
        var previous = playlistList.getSelectedValue();
        var selectedCategory = (Category) category.getSelectedItem();
        changingList = true; playlistModel.clear();
        int selectedIndex = 0;
        for (var playlist : playlists) {
            if (selectedCategory == Category.ALL || playlist.category() == selectedCategory) {
                if (previous != null && playlist.id() == previous.id()) selectedIndex = playlistModel.size();
                playlistModel.addElement(playlist);
            }
        }
        changingList = false;
        if (!playlistModel.isEmpty()) playlistList.setSelectedIndex(selectedIndex);
        else if (controller.session() != null) {
            controller.clearSongs();
            var selected = (Category) category.getSelectedItem();
            showContent(new PlaylistContent(new Playlist(0, selected.toString(), 0, "", selected), List.of(), 0));
            showStatus("此分类暂无歌单");
        }
    }
    void showContent(PlaylistContent content) {
        songs.setSongs(content.songs()); sorter.setSortKeys(null); filter.setText("");
        title.setText(content.playlist().name()); title.setToolTipText(content.playlist().name());
        views.setSelectedIndex(0);
        long missing = content.songs().stream().filter(song -> !song.detailAvailable()).count();
        var message = content.songs().size() + " 首歌曲";
        if (content.expectedCount() != content.songs().size()) message += " · 歌单标记 " + content.expectedCount() + " 首";
        if (missing > 0) message += " · " + missing + " 首详情暂不可用";
        showStatus(message);
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        for (var dialog : List.copyOf(dialogs)) dialog.dispose();
        controller.close(); queue.close();
    }
}
