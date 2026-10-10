package com.ncmbreaker.ui.music;

import com.ncmbreaker.netease.auth.LoginSession;
import com.ncmbreaker.netease.music.MusicModels.*;
import com.ncmbreaker.netease.music.MusicException;
import com.ncmbreaker.playlist.MusicLink;
import com.ncmbreaker.ui.UiStyle;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.IntConsumer;

public final class MusicPanel extends JPanel implements AutoCloseable {
    private final MusicController controller = new MusicController(this);
    private final MusicTableModel songs = new MusicTableModel();
    private final DownloadQueue queue = new DownloadQueue();
    private final JTable songTable = new JTable(songs);
    private final TableRowSorter<MusicTableModel> sorter = new TableRowSorter<>(songs);
    private final DefaultListModel<Playlist> playlistModel = new DefaultListModel<>();
    private final JList<Playlist> playlistList = new JList<>(playlistModel);
    private final JComboBox<Category> category = new JComboBox<>(Category.values());
    private final JToggleButton single = new JToggleButton("单曲", true);
    private final JToggleButton playlistLink = new JToggleButton("歌单");
    private final JTextField link = UiStyle.searchField("歌曲链接或 ID");
    private final JTextField filter = UiStyle.searchField("筛选歌曲、歌手、专辑");
    private final JTextField output = new JTextField(Path.of(System.getProperty("user.home"), "Music", "NCM Breaker").toString());
    private final JCheckBoxMenuItem tags = new JCheckBoxMenuItem("写入歌曲标签", true);
    private final JCheckBoxMenuItem cover = new JCheckBoxMenuItem("嵌入专辑封面", true);
    private final JLabel title = new JLabel("音乐下载");
    private final JLabel status = new JLabel("请先登录网易云账号");
    private final JButton refresh = UiStyle.button("\u21bb");
    private final JButton open = UiStyle.button("获取歌曲");
    private final DownloadPanel downloads = new DownloadPanel(queue, this::chooseQuality, this::outputDirectory);
    private final List<QualityDialog> dialogs = new ArrayList<>();
    private List<Playlist> playlists = List.of();
    private String accountName = "";
    private boolean changingList;
    private boolean closed;
    private Runnable loginAction = () -> { };
    private Runnable showDownloadsAction = () -> { };
    private IntConsumer taskCountListener = count -> { };

    public MusicPanel() {
        super(new BorderLayout());
        add(toolbar(), BorderLayout.NORTH);
        var split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sidebar(), content());
        split.setBorder(BorderFactory.createEmptyBorder());
        split.setDividerSize(1);
        split.setDividerLocation(244);
        split.setResizeWeight(0);
        add(split, BorderLayout.CENTER);
        add(destination(), BorderLayout.SOUTH);
        queue.addTableModelListener(event -> taskCountListener.accept(queue.getRowCount()));
        resetAccount(null);
    }

    public void setLoginAction(Runnable action) { loginAction = action; }
    public void setShowDownloadsAction(Runnable action) { showDownloadsAction = action; }
    public void setTaskCountListener(IntConsumer listener) {
        taskCountListener = listener;
        listener.accept(queue.getRowCount());
    }
    public JComponent downloadView() { return downloads; }

    public void setSession(LoginSession session) {
        if (closed) return;
        var previous = controller.session();
        if (previous != null && previous != session) queue.cancelSession(previous);
        for (var dialog : List.copyOf(dialogs)) dialog.dispose();
        controller.setSession(session);
    }

    private JPanel toolbar() {
        var bar = new JPanel(new BorderLayout(12, 0));
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, UiStyle.border()),
                BorderFactory.createEmptyBorder(18, 24, 18, 24)));
        var modes = new JPanel(new GridLayout(1, 2));
        var group = new ButtonGroup();
        for (var mode : new JToggleButton[]{single, playlistLink}) {
            UiStyle.button(mode);
            mode.setPreferredSize(new Dimension(68, 42));
            group.add(mode);
            modes.add(mode);
            mode.addItemListener(event -> updateLinkType());
        }
        single.setName("music.single");
        playlistLink.setName("music.playlist");
        bar.add(modes, BorderLayout.WEST);
        link.setName("music.link");
        link.setPreferredSize(new Dimension(0, 42));
        bar.add(link, BorderLayout.CENTER);
        var actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        var importButton = UiStyle.button("导入歌单");
        importButton.setIcon(UIManager.getIcon("FileView.fileIcon"));
        importButton.addActionListener(event -> importPlaylist());
        open.setPreferredSize(new Dimension(108, 42));
        importButton.setPreferredSize(new Dimension(120, 42));
        open.addActionListener(event -> controller.open(link.getText(), single.isSelected()));
        link.addActionListener(event -> controller.open(link.getText(), single.isSelected()));
        link.getDocument().addDocumentListener(new DocumentListener() {
            private void update() {
                var text = link.getText().strip();
                if (text.isEmpty() || text.chars().allMatch(Character::isDigit)) return;
                try { selectLinkType(MusicLink.parse(text).song()); }
                catch (MusicException ignored) { }
            }
            @Override public void insertUpdate(DocumentEvent event) { update(); }
            @Override public void removeUpdate(DocumentEvent event) { update(); }
            @Override public void changedUpdate(DocumentEvent event) { update(); }
        });
        updateLinkType();
        actions.add(open);
        actions.add(importButton);
        bar.add(actions, BorderLayout.EAST);
        return bar;
    }

    void selectLinkType(boolean song) {
        (song ? single : playlistLink).setSelected(true);
    }

    private void updateLinkType() {
        for (var mode : new JToggleButton[]{single, playlistLink}) {
            mode.setBackground(UIManager.getColor(mode.isSelected() ? "List.selectionBackground" : "Button.background"));
            mode.setForeground(UIManager.getColor(mode.isSelected() ? "List.selectionForeground" : "Button.foreground"));
        }
        UiStyle.searchHint(link, single.isSelected() ? "歌曲链接或 ID" : "歌单链接或 ID");
        open.setText(single.isSelected() ? "获取歌曲" : "获取歌单");
    }

    private JPanel sidebar() {
        var side = new JPanel(new BorderLayout(0, 12));
        side.setBackground(UIManager.getColor("TabbedPane.tabAreaBackground"));
        side.setBorder(BorderFactory.createEmptyBorder(18, 14, 12, 14));
        side.setMinimumSize(new Dimension(206, 160));
        var heading = new JPanel(new BorderLayout(0, 10));
        heading.setOpaque(false);
        var caption = new JPanel(new BorderLayout());
        caption.setOpaque(false);
        var label = new JLabel("我的歌单");
        label.setForeground(UiStyle.muted());
        caption.add(label, BorderLayout.CENTER);
        refresh.setToolTipText("刷新我的歌单");
        refresh.setFont(new Font(Font.DIALOG, Font.PLAIN, 21));
        refresh.setBorder(BorderFactory.createEmptyBorder());
        refresh.setPreferredSize(new Dimension(32, 28));
        refresh.addActionListener(event -> controller.refresh());
        caption.add(refresh, BorderLayout.EAST);
        heading.add(caption, BorderLayout.NORTH);
        category.setPreferredSize(new Dimension(0, 34));
        UiStyle.comboBox(category);
        heading.add(category, BorderLayout.SOUTH);
        side.add(heading, BorderLayout.NORTH);
        playlistList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        playlistList.setFixedCellHeight(52);
        playlistList.setFixedCellWidth(1);
        playlistList.setBackground(side.getBackground());
        playlistList.setCellRenderer(new PlaylistRenderer());
        playlistList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting() && !changingList && playlistList.getSelectedValue() != null) {
                controller.loadPlaylist(playlistList.getSelectedValue());
            }
        });
        category.addActionListener(event -> filterPlaylists());
        var scroll = UiStyle.scrollPane(playlistList);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        side.add(scroll, BorderLayout.CENTER);
        return side;
    }

    private JPanel content() {
        var panel = new JPanel(new BorderLayout(0, 18));
        panel.setBorder(BorderFactory.createEmptyBorder(24, 24, 12, 24));
        panel.setMinimumSize(new Dimension(480, 160));
        var heading = new JPanel(new BorderLayout(18, 0));
        var names = new JPanel(new BorderLayout(0, 6));
        title.setFont(title.getFont().deriveFont(22f));
        title.putClientProperty("html.disable", Boolean.TRUE);
        names.add(title, BorderLayout.NORTH);
        status.setForeground(UiStyle.muted());
        status.putClientProperty("html.disable", Boolean.TRUE);
        status.setPreferredSize(new Dimension(0, 24));
        names.add(status, BorderLayout.SOUTH);
        heading.add(names, BorderLayout.CENTER);
        var search = new JPanel(new GridBagLayout());
        filter.setPreferredSize(new Dimension(214, 40));
        search.add(filter);
        heading.add(search, BorderLayout.EAST);
        panel.add(heading, BorderLayout.NORTH);
        configureSongTable();
        panel.add(UiStyle.scrollPane(songTable), BorderLayout.CENTER);
        return panel;
    }

    private JPanel destination() {
        var bar = new JPanel(new BorderLayout(12, 0));
        bar.setBackground(UIManager.getColor("TabbedPane.tabAreaBackground"));
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, UiStyle.border()),
                BorderFactory.createEmptyBorder(12, 24, 12, 24)));
        var path = new JPanel(new BorderLayout(10, 0));
        path.setOpaque(false);
        path.add(new JLabel("保存到"), BorderLayout.WEST);
        output.setEditable(false);
        output.setOpaque(false);
        output.setBorder(BorderFactory.createEmptyBorder());
        output.setForeground(UiStyle.muted());
        output.setToolTipText(output.getText());
        path.add(output, BorderLayout.CENTER);
        bar.add(path, BorderLayout.CENTER);
        var actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        var browse = UiStyle.button("更改目录");
        browse.setIcon(UIManager.getIcon("FileView.directoryIcon"));
        browse.addActionListener(event -> selectDirectory());
        var settings = UiStyle.button("输出设置");
        var menu = new JPopupMenu();
        tags.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        cover.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        menu.add(tags); menu.add(cover);
        settings.addActionListener(event -> menu.show(settings,
                settings.getWidth() - menu.getPreferredSize().width, -menu.getPreferredSize().height - 4));
        actions.add(browse); actions.add(settings);
        bar.add(actions, BorderLayout.EAST);
        return bar;
    }

    private void configureSongTable() {
        UiStyle.table(songTable, 66);
        songTable.setName("music.songs");
        songTable.setRowSorter(sorter);
        songTable.setDefaultRenderer(Song.class, new SongRenderer());
        var columns = songTable.getColumnModel();
        columns.getColumn(0).setMaxWidth(48);
        columns.getColumn(1).setPreferredWidth(290);
        columns.getColumn(2).setPreferredWidth(210);
        columns.getColumn(3).setMinWidth(62);
        columns.getColumn(3).setMaxWidth(70);
        columns.getColumn(4).setMinWidth(48);
        columns.getColumn(4).setMaxWidth(56);
        var action = new RowAction(row -> chooseQuality(songs.song(row)));
        columns.getColumn(4).setCellRenderer(action);
        columns.getColumn(4).setCellEditor(action);
        sorter.setSortable(4, false);
        sorter.setComparator(1, Comparator.comparing(Song::title, String.CASE_INSENSITIVE_ORDER));
        filter.getDocument().addDocumentListener(new DocumentListener() {
            private void update() {
                var query = filter.getText().strip().toLowerCase(java.util.Locale.ROOT);
                sorter.setRowFilter(query.isEmpty() ? null : new RowFilter<MusicTableModel, Integer>() {
                    @Override public boolean include(Entry<? extends MusicTableModel, ? extends Integer> entry) {
                        var song = songs.song(entry.getIdentifier());
                        return (song.title() + " " + song.artistText() + " " + song.album())
                                .toLowerCase(java.util.Locale.ROOT).contains(query);
                    }
                });
            }
            @Override public void insertUpdate(DocumentEvent event) { update(); }
            @Override public void removeUpdate(DocumentEvent event) { update(); }
            @Override public void changedUpdate(DocumentEvent event) { update(); }
        });
        songTable.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(KeyStroke.getKeyStroke("ENTER"), "download-song");
        songTable.getActionMap().put("download-song", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) {
                if (songTable.getSelectedRow() >= 0) {
                    chooseQuality(songs.song(songTable.convertRowIndexToModel(songTable.getSelectedRow())));
                }
            }
        });
    }

    private Path outputDirectory() { return Path.of(output.getText()).toAbsolutePath().normalize(); }

    private void chooseQuality(Song song) {
        var session = controller.session();
        if (session == null || session.account() == null) {
            showStatus("请先登录网易云账号"); loginAction.run(); return;
        }
        var directory = outputDirectory();
        var dialog = new QualityDialog(SwingUtilities.getWindowAncestor(this), session, song, (resolved, quality) -> {
            if (closed || controller.session() != session) return;
            boolean added = queue.add(session, resolved, quality, directory, tags.isSelected(), cover.isSelected());
            showStatus((added ? "已加入下载任务：" : "下载任务已存在：") + resolved.title() + " · " + quality);
            showDownloadsAction.run();
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
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            controller.importFile(chooser.getSelectedFile().toPath());
        }
    }

    private void selectDirectory() {
        var chooser = new JFileChooser(output.getText());
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("选择保存目录");
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            output.setText(chooser.getSelectedFile().getAbsolutePath());
            output.setToolTipText(output.getText());
            output.setCaretPosition(0);
        }
    }

    void resetAccount(LoginSession.Account value) {
        playlists = List.of();
        changingList = true; playlistModel.clear(); changingList = false;
        if (songTable.isEditing()) songTable.getCellEditor().cancelCellEditing();
        songs.setSongs(List.of());
        title.setText("音乐下载");
        accountName = value == null ? "" : value.nickname();
        open.setEnabled(value != null); refresh.setEnabled(value != null); category.setEnabled(value != null);
        showStatus(value == null ? "请先登录网易云账号" : "正在读取我的歌单");
        songTable.setEnabled(true);
        downloads.setSignedIn(value != null);
    }
    void libraryLoading(boolean loading) {
        refresh.setEnabled(!loading && controller.session() != null);
        if (loading) showStatus("正在读取我的歌单");
    }
    void songsLoading(boolean loading) {
        if (songTable.isEditing()) songTable.getCellEditor().cancelCellEditing();
        songTable.setEnabled(!loading);
        if (loading) showStatus("正在读取歌曲");
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
            showContent(new PlaylistContent(new Playlist(0, selectedCategory.toString(), 0, "", selectedCategory), List.of(), 0));
            showStatus("此分类暂无歌单");
        }
    }
    void showContent(PlaylistContent content) {
        songs.setSongs(content.songs()); sorter.setSortKeys(null); filter.setText("");
        changingList = true;
        playlistList.clearSelection();
        for (int index = 0; index < playlistModel.size(); index++) {
            if (playlistModel.get(index).id() == content.playlist().id()) {
                playlistList.setSelectedIndex(index);
                break;
            }
        }
        changingList = false;
        var playlist = playlists.stream().filter(item -> item.id() == content.playlist().id())
                .findFirst().orElse(content.playlist());
        title.setText(playlist.category() == Category.LIKED ? "喜欢的音乐" : playlist.name());
        title.setToolTipText(playlist.name());
        long missing = content.songs().stream().filter(song -> !song.detailAvailable()).count();
        var message = (accountName.isBlank() ? "" : accountName + " · ") + content.songs().size() + " 首歌曲";
        if (content.expectedCount() != content.songs().size()) message += " · 歌单标记 " + content.expectedCount() + " 首";
        if (missing > 0) message += " · " + missing + " 首详情暂不可用";
        showStatus(message);
    }

    private static final class PlaylistRenderer extends JPanel implements ListCellRenderer<Playlist> {
        private final JLabel name = new JLabel();
        private final JLabel count = new JLabel();
        PlaylistRenderer() {
            super(new BorderLayout(8, 0));
            setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
            name.putClientProperty("html.disable", Boolean.TRUE);
            add(name, BorderLayout.CENTER); add(count, BorderLayout.EAST);
        }
        @Override public Component getListCellRendererComponent(JList<? extends Playlist> list,
                Playlist value, int index, boolean selected, boolean focus) {
            name.setText(value.category() == Category.LIKED ? "喜欢的音乐" : value.name());
            count.setText(Integer.toString(value.trackCount()));
            name.setForeground(selected ? list.getSelectionForeground() : list.getForeground());
            count.setForeground(selected ? list.getSelectionForeground() : UiStyle.muted());
            setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            setToolTipText(value.name() + " · " + value.trackCount() + " 首");
            return this;
        }
    }

    private static final class SongRenderer extends JPanel implements TableCellRenderer {
        private final JLabel name = new JLabel();
        private final JLabel artist = new JLabel();
        SongRenderer() {
            super(new GridLayout(2, 1, 0, 2));
            setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
            name.putClientProperty("html.disable", Boolean.TRUE);
            artist.putClientProperty("html.disable", Boolean.TRUE);
            name.setFont(name.getFont().deriveFont(15f));
            add(name); add(artist);
        }
        @Override public Component getTableCellRendererComponent(JTable table, Object value,
                boolean selected, boolean focus, int row, int column) {
            var song = (Song) value;
            name.setText(song.title() + (song.detailAvailable() ? "" : "（详情暂不可用）"));
            artist.setText(song.artistText());
            name.setForeground(selected ? table.getSelectionForeground() : table.getForeground());
            artist.setForeground(UiStyle.muted());
            setBackground(selected ? table.getSelectionBackground() : table.getBackground());
            setToolTipText(song.title() + " · " + song.artistText());
            return this;
        }
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        for (var dialog : List.copyOf(dialogs)) dialog.dispose();
        controller.close(); downloads.close(); queue.close();
    }
}
