package com.ncmbreaker.ui.music;

import com.ncmbreaker.netease.auth.LoginSession;
import com.ncmbreaker.netease.music.MusicModels.*;
import com.ncmbreaker.netease.music.MusicException;
import com.ncmbreaker.playlist.MusicLink;
import com.ncmbreaker.playlist.PlaylistWorkbook;
import com.ncmbreaker.ui.UiStyle;
import com.ncmbreaker.app.AppSettingsStore;
import com.ncmbreaker.app.AppSettingsStore.Directory;
import com.ncmbreaker.app.AppSettingsStore.Option;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ItemEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.IntConsumer;

public final class MusicPanel extends JPanel implements AutoCloseable {
    private final AppSettingsStore settings;
    private final MusicController controller = new MusicController(this);
    private final MusicTableModel songs = new MusicTableModel();
    private final DownloadQueue queue = new DownloadQueue();
    private final JTable songTable = new JTable(songs);
    private final TableRowSorter<MusicTableModel> sorter = new TableRowSorter<>(songs);
    private final CollectionSearchTableModel searchCollections = new CollectionSearchTableModel();
    private final JTable searchCollectionTable = new JTable(searchCollections);
    private final JScrollPane songScroll = UiStyle.scrollPane(songTable);
    private final JScrollPane collectionScroll = UiStyle.scrollPane(searchCollectionTable);
    private final JPanel resultTables = new JPanel(new CardLayout());
    private final JPanel filterArea = new JPanel(new GridBagLayout());
    private final JPanel pagination = new JPanel(new BorderLayout(12, 0));
    private final JLabel pageRange = new JLabel();
    private final JLabel pageNumber = new JLabel();
    private final JButton previousPage = UiStyle.button("\u2039");
    private final JButton nextPage = UiStyle.button("\u203a");
    private final DefaultListModel<Playlist> playlistModel = new DefaultListModel<>();
    private final JList<Playlist> playlistList = new JList<>(playlistModel);
    private final JComboBox<Category> category = new JComboBox<>(Category.values());
    private final JToggleButton single = new JToggleButton("歌曲", true);
    private final JToggleButton playlistLink = new JToggleButton("歌单");
    private final JToggleButton albumLink = new JToggleButton("专辑");
    private final JComboBox<String> lookupMode = new JComboBox<>(new String[]{"关键词搜索", "链接或 ID"});
    private final JTextField link = UiStyle.searchField("输入歌曲、歌手关键词");
    private final JTextField filter = UiStyle.searchField("筛选歌曲、歌手、专辑");
    private final JTextField output = new JTextField();
    private final JCheckBoxMenuItem tags = new JCheckBoxMenuItem("写入歌曲标签", true);
    private final JCheckBoxMenuItem cover = new JCheckBoxMenuItem("嵌入专辑封面", true);
    private final JLabel title = new JLabel("音乐下载");
    private final JLabel status = new JLabel("请先登录网易云账号");
    private final JButton refresh = UiStyle.button("\u21bb");
    private final JButton open = UiStyle.button("获取歌曲");
    private final JButton export = UiStyle.button("导出歌单");
    private final JButton downloadAll = UiStyle.button("下载整个歌单");
    private final JButton downloadSelected = UiStyle.button("下载所选");
    private final JButton returnPlaylist = UiStyle.button("返回原歌单");
    private final JButton returnResults = UiStyle.button("返回搜索结果");
    private final JLabel libraryStatus = new JLabel(" ");
    private final DownloadPanel downloads = new DownloadPanel(queue,
            task -> chooseQuality(task.song, task.directory), this::outputDirectory);
    private final List<JDialog> dialogs = new ArrayList<>();
    private final ArrayDeque<DownloadQueue.BatchResult> batchResults = new ArrayDeque<>();
    private final Timer resultTimer = new Timer(250, event -> showNextBatchResult());
    private List<Playlist> playlists = List.of();
    private PlaylistContent currentContent;
    private SearchResult searchResult;
    private MusicViewState returnSearch;
    private MusicViewState returnView;
    private DownloadQueue.Source retrySource;
    private DownloadQueue.Destination retryDestination;
    private boolean choosingBatch;
    private boolean showingResult;
    private boolean songsLoading;
    private boolean exporting;
    private String accountName = "";
    private boolean changingList;
    private boolean changingLookup;
    private long viewGeneration;
    private boolean closed;
    private Runnable loginAction = () -> { };
    private Runnable showDownloadsAction = () -> { };
    private Runnable showMusicAction = () -> { };
    private Runnable taskSuccessListener = () -> { };
    private IntConsumer taskCountListener = count -> { };

    public MusicPanel(AppSettingsStore settings) {
        super(new BorderLayout());
        this.settings = settings;
        setOutputDirectory(defaultDownloadDirectory());
        tags.setSelected(settings.option(Option.DOWNLOAD_TAGS));
        cover.setSelected(settings.option(Option.DOWNLOAD_COVER));
        add(toolbar(), BorderLayout.NORTH);
        var split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sidebar(), content());
        split.setBorder(BorderFactory.createEmptyBorder());
        split.setDividerSize(1);
        split.setDividerLocation(244);
        split.setResizeWeight(0);
        add(split, BorderLayout.CENTER);
        add(destination(), BorderLayout.SOUTH);
        queue.addTableModelListener(event -> taskCountListener.accept(queue.getRowCount()));
        queue.setSuccessListener(this::taskSucceeded);
        queue.setBatchListener(result -> {
            if (closed || result.session() != controller.session()) return;
            batchResults.addLast(result);
            resultTimer.start();
        });
        resetAccount(null);
        tags.addActionListener(event -> settings.setOption(Option.DOWNLOAD_TAGS, tags.isSelected()));
        cover.addActionListener(event -> settings.setOption(Option.DOWNLOAD_COVER, cover.isSelected()));
    }

    public void setLoginAction(Runnable action) { loginAction = action; }
    public void setShowDownloadsAction(Runnable action) { showDownloadsAction = action; }
    public void setShowMusicAction(Runnable action) { showMusicAction = action; }
    public void setTaskSuccessListener(Runnable listener) { taskSuccessListener = listener; }
    public boolean isBusy() {
        return controller.isBusy() || queue.hasPendingTasks() || choosingBatch || showingResult || !batchResults.isEmpty();
    }
    void taskSucceeded() { if (!closed) taskSuccessListener.run(); }
    public void setTaskCountListener(IntConsumer listener) {
        taskCountListener = listener;
        listener.accept(queue.getRowCount());
    }
    public JComponent downloadView() { return downloads; }

    public void setSession(LoginSession session) {
        if (closed) return;
        var previous = controller.session();
        if (previous != null && previous != session) queue.cancelSession(previous);
        batchResults.clear(); resultTimer.stop();
        for (var dialog : List.copyOf(dialogs)) dialog.dispose();
        controller.setSession(session);
    }

    private JPanel toolbar() {
        var bar = new JPanel(new BorderLayout(12, 0));
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, UiStyle.border()),
                BorderFactory.createEmptyBorder(18, 24, 18, 24)));
        var modes = new JPanel(new GridLayout(1, 3));
        var group = new ButtonGroup();
        for (var mode : new JToggleButton[]{single, playlistLink, albumLink}) {
            UiStyle.button(mode);
            mode.setPreferredSize(new Dimension(68, 42));
            group.add(mode);
            modes.add(mode);
            mode.addItemListener(event -> {
                updateLinkType();
                if (!changingLookup && event.getStateChange() == ItemEvent.SELECTED
                        && keywordInput() && !link.getText().isBlank()) submitLookup();
            });
        }
        single.setName("music.single");
        playlistLink.setName("music.playlist");
        albumLink.setName("music.album");
        var inputModes = new JPanel(new BorderLayout(12, 0));
        inputModes.add(modes, BorderLayout.WEST);
        lookupMode.setName("music.lookupMode");
        lookupMode.setPreferredSize(new Dimension(132, 42));
        UiStyle.comboBox(lookupMode);
        lookupMode.addActionListener(event -> {
            if (!changingLookup) controller.clearSongs();
            updateLinkType();
        });
        inputModes.add(lookupMode, BorderLayout.CENTER);
        bar.add(inputModes, BorderLayout.WEST);
        link.setName("music.link");
        link.setPreferredSize(new Dimension(0, 42));
        bar.add(link, BorderLayout.CENTER);
        var actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        export.setIcon(UIManager.getIcon("FileView.floppyDriveIcon"));
        export.setToolTipText("将完整歌单导出为 XLSX");
        export.addActionListener(event -> exportPlaylist());
        open.setPreferredSize(new Dimension(108, 42));
        open.setName("music.lookup");
        open.addActionListener(event -> submitLookup());
        link.addActionListener(event -> submitLookup());
        link.getDocument().addDocumentListener(new DocumentListener() {
            private void update() {
                if (keywordInput()) return;
                var text = link.getText().strip();
                if (text.isEmpty() || text.chars().allMatch(Character::isDigit)) return;
                try { selectLinkType(MusicLink.parse(text).type()); }
                catch (MusicException ignored) { }
            }
            @Override public void insertUpdate(DocumentEvent event) { update(); }
            @Override public void removeUpdate(DocumentEvent event) { update(); }
            @Override public void changedUpdate(DocumentEvent event) { update(); }
        });
        updateLinkType();
        actions.add(open);
        bar.add(actions, BorderLayout.EAST);
        return bar;
    }

    private JToggleButton lookupButton(SearchType type) {
        return switch (type) {
            case SONG -> single;
            case PLAYLIST -> playlistLink;
            case ALBUM -> albumLink;
        };
    }

    private SearchType lookupType() {
        return albumLink.isSelected() ? SearchType.ALBUM : single.isSelected() ? SearchType.SONG : SearchType.PLAYLIST;
    }

    void selectLinkType(SearchType type) {
        changingLookup = true;
        try { lookupButton(type).setSelected(true); }
        finally { changingLookup = false; }
        updateLinkType();
    }

    private boolean keywordInput() { return lookupMode.getSelectedIndex() == 0; }

    private void submitLookup() {
        if (keywordInput()) controller.search(link.getText(), lookupType(), 0);
        else controller.open(link.getText(), lookupType());
    }

    private void updateLinkType() {
        for (var mode : new JToggleButton[]{single, playlistLink, albumLink}) {
            mode.setBackground(UIManager.getColor(mode.isSelected() ? "List.selectionBackground" : "Button.background"));
            mode.setForeground(UIManager.getColor(mode.isSelected() ? "List.selectionForeground" : "Button.foreground"));
        }
        var type = lookupType();
        UiStyle.searchHint(link, keywordInput()
                ? type == SearchType.SONG ? "输入歌曲、歌手关键词" : "输入" + type + "关键词"
                : type + "链接或 ID");
        open.setText(keywordInput() ? "搜索" : "获取" + type);
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
        var bottom = new JPanel(new BorderLayout(0, 10));
        bottom.setOpaque(false);
        libraryStatus.setForeground(UiStyle.muted());
        libraryStatus.putClientProperty("html.disable", Boolean.TRUE);
        bottom.add(libraryStatus, BorderLayout.NORTH);
        var importButton = UiStyle.button("导入歌单");
        importButton.setIcon(UIManager.getIcon("FileView.fileIcon"));
        importButton.addActionListener(event -> importPlaylist());
        bottom.add(importButton, BorderLayout.SOUTH);
        side.add(bottom, BorderLayout.SOUTH);
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
        filter.setPreferredSize(new Dimension(214, 40));
        filterArea.add(filter);
        heading.add(filterArea, BorderLayout.EAST);
        var top = new JPanel(new BorderLayout(0, 14));
        top.add(heading, BorderLayout.NORTH);
        var actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        downloadAll.setName("music.downloadAll");
        downloadSelected.setName("music.downloadSelected");
        downloadAll.setPreferredSize(new Dimension(132, 38));
        downloadSelected.setPreferredSize(new Dimension(120, 38));
        returnPlaylist.setPreferredSize(new Dimension(124, 38));
        returnResults.setPreferredSize(new Dimension(132, 38));
        export.setPreferredSize(new Dimension(120, 38));
        downloadAll.setToolTipText("下载当前完整歌单");
        downloadSelected.setToolTipText("下载选中的歌曲");
        downloadAll.addActionListener(event -> chooseBatch(true));
        downloadSelected.addActionListener(event -> chooseBatch(false));
        returnPlaylist.addActionListener(event -> {
            var previous = returnView;
            if (previous == null) return;
            controller.clearSongs();
            restoreView(previous);
        });
        returnResults.addActionListener(event -> {
            var previous = returnSearch;
            if (previous == null) return;
            controller.clearSongs();
            restoreView(previous);
        });
        returnPlaylist.setVisible(false);
        returnResults.setVisible(false);
        actions.add(returnResults); actions.add(returnPlaylist);
        actions.add(downloadAll); actions.add(downloadSelected); actions.add(export);
        top.add(actions, BorderLayout.SOUTH);
        panel.add(top, BorderLayout.NORTH);
        configureSongTable();
        configureCollectionTable();
        resultTables.add(songScroll, "songs");
        resultTables.add(collectionScroll, "playlists");
        panel.add(resultTables, BorderLayout.CENTER);
        previousPage.setName("music.previousPage"); nextPage.setName("music.nextPage");
        previousPage.setToolTipText("上一页"); nextPage.setToolTipText("下一页");
        previousPage.setPreferredSize(new Dimension(36, 34));
        nextPage.setPreferredSize(new Dimension(36, 34));
        previousPage.addActionListener(event -> changeSearchPage(-1));
        nextPage.addActionListener(event -> changeSearchPage(1));
        pageRange.setForeground(UiStyle.muted()); pageNumber.setForeground(UiStyle.muted());
        pagination.add(pageRange, BorderLayout.WEST);
        var pageControls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        pageControls.add(previousPage); pageControls.add(pageNumber); pageControls.add(nextPage);
        pagination.add(pageControls, BorderLayout.EAST);
        pagination.setVisible(false);
        panel.add(pagination, BorderLayout.SOUTH);
        return panel;
    }

    private void configureCollectionTable() {
        UiStyle.table(searchCollectionTable, 66);
        searchCollectionTable.setName("music.searchCollections");
        var columns = searchCollectionTable.getColumnModel();
        columns.getColumn(0).setMaxWidth(58);
        columns.getColumn(1).setPreferredWidth(310);
        columns.getColumn(2).setPreferredWidth(150);
        columns.getColumn(3).setMinWidth(70);
        columns.getColumn(3).setMaxWidth(84);
        columns.getColumn(4).setMinWidth(48);
        columns.getColumn(4).setMaxWidth(56);
        var action = new RowAction(this::openSearchCollection, "打开歌曲列表");
        columns.getColumn(4).setCellRenderer(action);
        columns.getColumn(4).setCellEditor(action);
        searchCollectionTable.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() != 2 || !searchCollectionTable.isEnabled()) return;
                int row = searchCollectionTable.rowAtPoint(event.getPoint());
                if (row >= 0 && searchCollectionTable.columnAtPoint(event.getPoint()) != 4) {
                    openSearchCollection(row);
                }
            }
        });
        searchCollectionTable.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(KeyStroke.getKeyStroke("ENTER"), "open-collection");
        searchCollectionTable.getActionMap().put("open-collection", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) {
                int row = searchCollectionTable.getSelectedRow();
                if (row >= 0 && searchCollectionTable.isEnabled()) openSearchCollection(row);
            }
        });
    }

    private void changeSearchPage(int direction) {
        if (searchResult == null || songsLoading) return;
        if (direction < 0 && searchResult.offset() == 0 || direction > 0 && !searchResult.hasMore()) return;
        controller.search(searchResult.query(), searchResult.type(), Math.max(0, searchResult.offset() + direction * searchResult.limit()));
    }

    private void openSearchCollection(int row) {
        if (songsLoading || searchResult == null || searchResult.type() == SearchType.SONG
                || row < 0 || row >= searchCollections.getRowCount()) return;
        controller.openSearchCollection(searchCollections.id(row), searchResult.type());
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
        songTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        songTable.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) updateBatchActions();
        });
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
                if (songTable.getSelectedRowCount() > 1) chooseBatch(false);
                else if (songTable.getSelectedRow() >= 0) {
                    chooseQuality(songs.song(songTable.convertRowIndexToModel(songTable.getSelectedRow())));
                }
            }
        });
    }

    private Path outputDirectory() { return Path.of(output.getText()).toAbsolutePath().normalize(); }

    private Path defaultDownloadDirectory() {
        return settings.directory(Directory.DOWNLOAD_OUTPUT, Path.of(System.getProperty("user.home"), "Music", "NCM Breaker"));
    }

    private void setOutputDirectory(Path directory) {
        output.setText(directory.toString());
        output.setToolTipText(output.getText());
        output.setCaretPosition(0);
    }

    private void chooseQuality(Song song) {
        chooseQuality(song, retryDestination == null ? outputDirectory() : retryDestination.directory());
    }

    private void chooseQuality(Song song, Path directory) {
        var session = controller.session();
        if (session == null || session.account() == null) {
            showStatus("请先登录网易云账号"); loginAction.run(); return;
        }
        var dialog = new QualityDialog(SwingUtilities.getWindowAncestor(this), session, song, (resolved, quality) -> {
            if (closed || controller.session() != session) return;
            boolean added = queue.add(session, resolved, quality, directory, tags.isSelected(), cover.isSelected());
            showStatus((added ? "已加入下载任务：" : "下载任务已存在：") + resolved.title() + "（" + quality + "）");
            showDownloadsAction.run();
        });
        showDialog(dialog);
    }

    private void chooseBatch(boolean entirePlaylist) {
        var session = controller.session();
        var content = currentSource();
        if (closed || choosingBatch || songsLoading || content == null) return;
        if (session == null || session.account() == null) {
            showStatus("请先登录网易云账号"); loginAction.run(); return;
        }
        var selected = entirePlaylist ? content.songs() : Arrays.stream(songTable.getSelectedRows())
                .mapToObj(row -> songs.song(songTable.convertRowIndexToModel(row))).toList();
        var unique = new LinkedHashMap<Long, Song>();
        for (var song : selected) unique.putIfAbsent(song.id(), song);
        var snapshot = List.copyOf(unique.values());
        if (snapshot.isEmpty()) return;
        var original = retrySource == null ? content : retrySource;
        var origin = returnView == null ? captureView() : returnView;
        var destination = retryDestination == null
                ? DownloadQueue.Destination.forSource(outputDirectory(), settings.option(Option.PLAYLIST_DIRECTORIES), original)
                : retryDestination;
        boolean writeTags = tags.isSelected();
        boolean writeCover = cover.isSelected();
        choosingBatch = true;
        updateBatchActions();
        try {
            var dialog = new BatchDownloadDialog(SwingUtilities.getWindowAncestor(this), content, snapshot,
                    original, settings.batchQuality(), destination, selection -> {
                if (closed || controller.session() != session) return;
                settings.setBatchPreferences(selection.quality(), original.playlist() == null
                        ? settings.option(Option.PLAYLIST_DIRECTORIES) : selection.destination().byPlaylist());
                if (returnView == origin) retryDestination = selection.destination();
                var batch = new DownloadQueue.Batch(session, content, original, origin, selection.destination());
                queue.addBatch(batch, snapshot, selection.quality(), writeTags, writeCover);
                showStatus(batch.tasks.isEmpty() ? "歌曲已在下载队列，未重复加入"
                        : "已加入 " + batch.tasks.size() + " 首歌曲"
                        + (batch.skipped == 0 ? "" : "，另有 " + batch.skipped + " 首已在下载队列"));
                showDownloadsAction.run();
            });
            showDialog(dialog);
        } finally {
            choosingBatch = false;
            updateBatchActions();
        }
    }

    private void updateBatchActions() {
        var content = currentSource();
        boolean available = !closed && !songsLoading && !choosingBatch && controller.session() != null
                && content != null && !content.songs().isEmpty();
        downloadAll.setVisible(currentContent != null);
        var type = currentContent == null ? CollectionType.PLAYLIST : currentContent.type();
        downloadAll.setText(returnView == null ? "下载整个" + type : "重试全部");
        downloadAll.setToolTipText(returnView == null ? "下载当前完整" + type : "重新下载本次失败的歌曲");
        downloadAll.setEnabled(available);
        int count = songTable.getSelectedRowCount();
        downloadSelected.setVisible(searchResult == null || searchResult.type() == SearchType.SONG);
        downloadSelected.setEnabled(available && count > 0);
        downloadSelected.setToolTipText(count > 0 ? "下载选中的 " + count + " 首歌曲" : "下载选中的歌曲");
        returnPlaylist.setVisible(returnView != null);
        var originalType = returnView != null && returnView.content() != null ? returnView.content().type() : CollectionType.PLAYLIST;
        returnPlaylist.setText(returnView != null && returnView.search() != null ? "返回搜索结果" : "返回原" + originalType);
        returnPlaylist.setEnabled(!songsLoading);
        returnResults.setVisible(returnSearch != null && returnView == null);
        returnResults.setEnabled(!songsLoading);
    }

    private DownloadQueue.Source currentSource() {
        if (searchResult != null) return searchResult.type() == SearchType.SONG
                ? DownloadQueue.Source.forSearch(searchResult) : null;
        return currentContent == null ? null : DownloadQueue.Source.forPlaylist(currentContent);
    }

    private void showDialog(JDialog dialog) {
        dialogs.add(dialog);
        try { dialog.setVisible(true); }
        finally { dialogs.remove(dialog); dialog.dispose(); }
    }

    private void showNextBatchResult() {
        if (closed || batchResults.isEmpty()) { resultTimer.stop(); return; }
        if (showingResult || !dialogs.isEmpty()) return;
        var owner = SwingUtilities.getWindowAncestor(this);
        if (owner == null || !owner.isShowing()) return;
        for (var window : Window.getWindows()) {
            if (window instanceof Dialog && window.isShowing()) return;
        }
        var result = batchResults.removeFirst();
        if (result.session() != controller.session()) return;
        showingResult = true;
        try {
            var dialog = new BatchResultDialog(owner, result);
            showDialog(dialog);
            if (dialog.showFailures() && !closed && result.session() == controller.session()) showFailedPlaylist(result);
        } finally { showingResult = false; }
    }

    private void showFailedPlaylist(DownloadQueue.BatchResult result) {
        controller.clearSongs();
        var failed = result.failedSongs();
        var playlist = new Playlist(0, "失败歌曲：" + result.original().name(), failed.size(), "", Category.CREATED);
        var type = result.original().playlist() == null ? CollectionType.PLAYLIST : result.original().playlist().type();
        showContent(new PlaylistContent(playlist, failed, failed.size(), type));
        returnView = result.view();
        retrySource = result.original();
        retryDestination = result.destination();
        setOutputDirectory(retryDestination.baseDirectory());
        updateBatchActions();
        showMusicAction.run();
    }

    private void importPlaylist() {
        var chooser = new JFileChooser(settings.chooserDirectory(Directory.PLAYLIST_IMPORT, null));
        chooser.setDialogTitle("导入歌单");
        chooser.setFileFilter(new FileNameExtensionFilter("Excel 歌单 (*.xlsx)", "xlsx"));
        chooser.setAcceptAllFileFilterUsed(false);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            settings.setDirectory(Directory.PLAYLIST_IMPORT, chooser.getSelectedFile().toPath().toAbsolutePath().getParent());
            controller.importFile(chooser.getSelectedFile().toPath());
        }
    }

    private void exportPlaylist() {
        var content = currentContent;
        if (content == null || content.type() == CollectionType.ALBUM || songsLoading || exporting) return;
        var chooser = new JFileChooser(settings.chooserDirectory(Directory.PLAYLIST_EXPORT, outputDirectory()));
        chooser.setDialogTitle("导出歌单");
        chooser.setFileFilter(new FileNameExtensionFilter("Excel 歌单 (*.xlsx)", "xlsx"));
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setSelectedFile(new java.io.File(chooser.getCurrentDirectory(), PlaylistWorkbook.fileName(content)));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        var path = chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
        if (!path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".xlsx")) {
            path = path.resolveSibling(path.getFileName() + ".xlsx");
        }
        boolean overwrite = Files.exists(path);
        if (overwrite && JOptionPane.showConfirmDialog(this, "文件已存在，是否覆盖？\n" + path,
                "导出歌单", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) return;
        settings.setDirectory(Directory.PLAYLIST_EXPORT, path.getParent());
        controller.exportFile(path, content, overwrite);
    }

    void exporting(boolean value) {
        exporting = value;
        updateExport();
        if (value) showStatus("正在导出歌单");
    }

    private void updateExport() {
        boolean album = currentContent != null && currentContent.type() == CollectionType.ALBUM;
        export.setVisible(searchResult == null && !album);
        export.setEnabled(!closed && !exporting && !songsLoading && currentContent != null && !album);
    }

    private void selectDirectory() {
        var chooser = new JFileChooser(AppSettingsStore.existingDirectory(outputDirectory()));
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("选择保存目录");
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            var directory = chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
            setOutputDirectory(directory);
            settings.setDirectory(Directory.DOWNLOAD_OUTPUT, directory);
            if (retryDestination != null) {
                retryDestination = DownloadQueue.Destination.forSource(directory, retryDestination.byPlaylist(), retrySource);
            }
        }
    }

    void resetAccount(LoginSession.Account value) {
        viewGeneration++;
        currentContent = null;
        searchResult = null;
        returnSearch = null;
        returnView = null;
        retrySource = null;
        retryDestination = null;
        setOutputDirectory(defaultDownloadDirectory());
        songsLoading = false;
        updateExport();
        playlists = List.of();
        changingList = true; playlistModel.clear(); changingList = false;
        if (songTable.isEditing()) songTable.getCellEditor().cancelCellEditing();
        songs.setSongs(List.of());
        if (searchCollectionTable.isEditing()) searchCollectionTable.getCellEditor().cancelCellEditing();
        searchCollections.setResult(null);
        ((CardLayout) resultTables.getLayout()).show(resultTables, "songs");
        pagination.setVisible(false);
        filterArea.setVisible(true);
        title.setText("音乐下载");
        accountName = value == null ? "" : value.nickname();
        open.setEnabled(value != null); refresh.setEnabled(value != null); category.setEnabled(value != null);
        showStatus(value == null ? "请先登录网易云账号" : "正在读取我的歌单");
        libraryStatus.setText(" "); libraryStatus.setToolTipText(null);
        songTable.setEnabled(true);
        searchCollectionTable.setEnabled(true);
        downloads.setSignedIn(value != null);
        updateBatchActions();
    }
    void libraryLoading(boolean loading) {
        refresh.setEnabled(!loading && controller.session() != null);
        if (loading) {
            libraryStatus.setText("正在读取歌单");
            if (currentContent == null && searchResult == null && !songsLoading) showStatus("正在读取我的歌单");
        }
    }
    void showLibraryError(String message) {
        libraryStatus.setText("歌单读取失败"); libraryStatus.setToolTipText(message);
        if (currentContent == null && searchResult == null && !songsLoading) showStatus(message);
    }
    void songsLoading(boolean loading) {
        songsLoading = loading;
        updateBatchActions();
        updateExport();
        if (songTable.isEditing()) songTable.getCellEditor().cancelCellEditing();
        if (searchCollectionTable.isEditing()) searchCollectionTable.getCellEditor().cancelCellEditing();
        songTable.setEnabled(!loading);
        searchCollectionTable.setEnabled(!loading);
        updatePagination();
        if (loading) showStatus("正在读取歌曲");
    }
    void showStatus(String text) { status.setText(text); status.setToolTipText(text); }
    void showPlaylists(List<Playlist> playlists, boolean maySelect) {
        boolean select = maySelect && searchResult == null && returnView == null && returnSearch == null
                && (currentContent == null || playlistList.getSelectedValue() != null);
        this.playlists = List.copyOf(playlists);
        libraryStatus.setText("已读取 " + playlists.size() + " 个歌单"); libraryStatus.setToolTipText(null);
        filterPlaylists(select);
    }
    private void filterPlaylists() {
        filterPlaylists(searchResult == null && returnSearch == null && returnView == null);
    }
    private void filterPlaylists(boolean selectPlaylist) {
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
        if (!selectPlaylist) return;
        if (!playlistModel.isEmpty()) playlistList.setSelectedIndex(selectedIndex);
        else if (controller.session() != null) {
            controller.clearSongs();
            showContent(new PlaylistContent(new Playlist(0, selectedCategory.toString(), 0, "", selectedCategory), List.of(), 0));
            showStatus("此分类暂无歌单");
        }
    }
    void showContent(PlaylistContent content) {
        viewGeneration++;
        searchResult = null;
        returnSearch = null;
        returnView = null;
        retrySource = null;
        retryDestination = null;
        setOutputDirectory(defaultDownloadDirectory());
        songs.setSongs(content.songs()); sorter.setSortKeys(null); filter.setText("");
        searchCollections.setResult(null);
        ((CardLayout) resultTables.getLayout()).show(resultTables, "songs");
        filterArea.setVisible(true);
        pagination.setVisible(false);
        changingList = true;
        playlistList.clearSelection();
        for (int index = 0; index < playlistModel.size(); index++) {
            if (content.type() == CollectionType.PLAYLIST && playlistModel.get(index).id() == content.playlist().id()) {
                playlistList.setSelectedIndex(index);
                break;
            }
        }
        changingList = false;
        var playlist = content.type() == CollectionType.ALBUM ? content.playlist()
                : playlists.stream().filter(item -> item.id() == content.playlist().id()).findFirst().orElse(content.playlist());
        currentContent = new PlaylistContent(playlist, content.songs(), content.expectedCount(), content.type());
        updateBatchActions();
        updateExport();
        title.setText(playlist.category() == Category.LIKED ? "喜欢的音乐" : playlist.name());
        title.setToolTipText(playlist.name());
        long missing = content.songs().stream().filter(song -> !song.detailAvailable()).count();
        var message = (accountName.isBlank() ? "" : accountName + "：") + content.songs().size() + " 首歌曲";
        if (content.expectedCount() != content.songs().size()) message += "，" + content.type() + "标记 " + content.expectedCount() + " 首";
        if (missing > 0) message += "，" + missing + " 首详情暂不可用";
        showStatus(message);
        revalidate(); repaint();
    }

    void showSearchResults(SearchResult result) {
        viewGeneration++;
        currentContent = null;
        searchResult = result;
        returnSearch = null; returnView = null; retrySource = null; retryDestination = null;
        setOutputDirectory(defaultDownloadDirectory());
        if (songTable.isEditing()) songTable.getCellEditor().cancelCellEditing();
        if (searchCollectionTable.isEditing()) searchCollectionTable.getCellEditor().cancelCellEditing();
        filter.setText(""); sorter.setSortKeys(null);
        songs.setSongs(result.songs(), result.offset());
        searchCollections.setResult(result);
        for (int column = 0; column < searchCollections.getColumnCount(); column++) {
            searchCollectionTable.getColumnModel().getColumn(column).setHeaderValue(searchCollections.getColumnName(column));
        }
        searchCollectionTable.getTableHeader().repaint();
        songTable.clearSelection(); searchCollectionTable.clearSelection();
        changingList = true; playlistList.clearSelection(); changingList = false;
        boolean song = result.type() == SearchType.SONG;
        ((CardLayout) resultTables.getLayout()).show(resultTables, song ? "songs" : "playlists");
        filterArea.setVisible(false);
        title.setText(result.type() + "搜索");
        title.setToolTipText(result.query());
        var count = result.total() < 0 ? "本页 " + result.size() : "共 " + result.total();
        showStatus("“" + result.query() + "”：" + count
                + (song ? " 首歌曲" : result.type() == SearchType.ALBUM ? " 张专辑" : " 个歌单"));
        if (result.size() == 0) showStatus("“" + result.query() + "”：本页没有搜索结果");
        updateBatchActions(); updateExport(); updatePagination();
        songScroll.getVerticalScrollBar().setValue(0);
        collectionScroll.getVerticalScrollBar().setValue(0);
        revalidate(); repaint();
    }

    void showSearchCollection(PlaylistContent content, MusicViewState previous) {
        showContent(content);
        returnSearch = previous;
        updateBatchActions();
    }

    private void updatePagination() {
        pagination.setVisible(searchResult != null);
        if (searchResult == null) return;
        var result = searchResult;
        int page = result.offset() / result.limit() + 1;
        pageNumber.setText(result.total() < 0 ? "第 " + page + " 页"
                : page + " / " + Math.max(page, (int) (((long) result.total() + result.limit() - 1) / result.limit())));
        pageRange.setText(result.size() == 0 ? "本页暂无结果"
                : (result.offset() + 1) + "–" + (result.offset() + result.size())
                + (result.total() >= 0 ? " / " + result.total() : ""));
        previousPage.setEnabled(!songsLoading && result.offset() > 0);
        nextPage.setEnabled(!songsLoading && result.hasMore());
    }

    MusicViewState captureView() {
        var selected = Arrays.stream(songTable.getSelectedRows())
                .mapToObj(row -> songs.song(songTable.convertRowIndexToModel(row)).id()).toList();
        boolean collectionSearch = searchResult != null && searchResult.type() != SearchType.SONG;
        int row = searchCollectionTable.getSelectedRow();
        long selectedCollection = collectionSearch && row >= 0 ? searchCollections.id(row) : 0;
        int scroll = (collectionSearch ? collectionScroll : songScroll).getVerticalScrollBar().getValue();
        return new MusicViewState(currentContent, searchResult, returnSearch, selected,
                new ArrayList<>(sorter.getSortKeys()), filter.getText(), scroll, selectedCollection,
                link.getText(), lookupType(), keywordInput());
    }

    private void restoreView(MusicViewState previous) {
        if (previous.search() != null) showSearchResults(previous.search());
        else if (previous.content() != null) showContent(previous.content());
        else return;
        returnSearch = previous.returnSearch();
        changingLookup = true;
        try {
            lookupMode.setSelectedIndex(previous.keywordInput() ? 0 : 1);
            lookupButton(previous.inputType()).setSelected(true);
            link.setText(previous.input());
        } finally { changingLookup = false; }
        updateLinkType();
        filter.setText(previous.filter()); sorter.setSortKeys(previous.sortKeys());
        for (int index = 0; index < songs.getRowCount(); index++) {
            if (previous.selectedSongs().contains(songs.song(index).id())) {
                int viewRow = songTable.convertRowIndexToView(index);
                if (viewRow >= 0) songTable.addRowSelectionInterval(viewRow, viewRow);
            }
        }
        for (int index = 0; index < searchCollections.getRowCount(); index++) {
            if (searchCollections.id(index) == previous.selectedCollection()) searchCollectionTable.setRowSelectionInterval(index, index);
        }
        updateBatchActions();
        long generation = viewGeneration;
        SwingUtilities.invokeLater(() -> {
            if (closed || generation != viewGeneration) return;
            var scroll = searchResult != null && searchResult.type() != SearchType.SONG ? collectionScroll : songScroll;
            scroll.getVerticalScrollBar().setValue(previous.scroll());
        });
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
            setToolTipText(value.name() + "：" + value.trackCount() + " 首");
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
            setToolTipText(song.title() + "（" + song.artistText() + "）");
            return this;
        }
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        resultTimer.stop(); batchResults.clear();
        for (var dialog : List.copyOf(dialogs)) dialog.dispose();
        controller.close(); downloads.close(); queue.close();
    }
}
