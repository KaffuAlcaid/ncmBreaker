package com.ncmbreaker.ui;

import com.ncmbreaker.NcmFileIO;
import com.ncmbreaker.audio.OutputPlanner;
import com.ncmbreaker.audio.ProcessingOptions;
import com.ncmbreaker.audio.TrackMetadata;
import com.ncmbreaker.audio.AudioProcessingService;
import com.ncmbreaker.download.DownloadCancellation;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class ProcessingOptionsPanel extends JPanel implements Scrollable {
    public record PreviewItem(TrackMetadata metadata, Path source, Path inputRoot, String format, int index) { }
    private final JCheckBox tags = check("写入歌曲信息");
    private final JCheckBox cover = check("嵌入专辑封面");
    private final JCheckBox enrich = check("联网补全歌曲与专辑资料");
    private final JComboBox<String> tagPolicy = new JComboBox<>(new String[]{"仅补缺失", "更新所选内容"});
    private final JCheckBox saveLyrics = check("保存同名 LRC");
    private final JCheckBox embedLyrics = check("嵌入歌词");
    private final JCheckBox translation = check("包含译文");
    private final JCheckBox romanization = check("包含音译");
    private final JCheckBox exportCover = check("导出独立封面");
    private final JComboBox<String> coverMode = new JComboBox<>(new String[]{"与音频同名", "按专辑保存"});
    private final JPanel coverModeRow;
    private final JCheckBox playlist = check("生成 M3U8 歌单");
    private final JTextField directory = new JTextField();
    private final JComboBox<ProcessingOptions.Layout> layout = new JComboBox<>();
    private final JComboBox<String> conflict = new JComboBox<>(new String[]{"添加编号", "跳过", "覆盖"});
    private final JTextField template = new JTextField();
    private final JPanel templateRow;
    private final JTextArea preview = text(3);
    private final JLabel problem = new JLabel(" ");
    private final List<Runnable> listeners = new ArrayList<>();
    private final String collection;
    private final boolean conversion;
    private List<PreviewItem> items;
    private List<PreviewItem> originalItems;
    private AudioProcessingService preparation;
    private SwingWorker<List<PreviewItem>, Void> metadataWorker;
    private DownloadCancellation metadataCancellation;
    private long metadataGeneration;
    private String metadataNotice = "";
    private final java.util.IdentityHashMap<Component, Boolean> enabledBeforeBusy = new java.util.IdentityHashMap<>();
    private String quality = "";
    private String downloadFormat = "flac";
    private boolean initializing = true;

    public ProcessingOptionsPanel(ProcessingOptions initial, Path destination, List<PreviewItem> items,
                                  String collection, boolean conversion, boolean online, boolean batch) {
        super();
        this.items = List.copyOf(items); this.originalItems = this.items; this.collection = collection; this.conversion = conversion;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setOpaque(false);
        tags.setSelected(initial.tags()); cover.setSelected(initial.cover()); enrich.setSelected(initial.enrich());
        tagPolicy.setSelectedIndex(initial.replaceExisting() ? 1 : 0);
        saveLyrics.setSelected(initial.saveLyrics()); embedLyrics.setSelected(initial.embedLyrics());
        translation.setSelected(initial.translation()); romanization.setSelected(initial.romanization());
        exportCover.setSelected(initial.exportCover()); coverMode.setSelectedIndex(initial.albumCover() ? 1 : 0);
        playlist.setSelected(initial.playlist()); playlist.setVisible(batch);
        directory.setText(destination.toAbsolutePath().normalize().toString());
        for (var choice : ProcessingOptions.Layout.values()) {
            if (!conversion && choice == ProcessingOptions.Layout.SOURCE) continue;
            if (!conversion && !batch && collection.isBlank() && choice == ProcessingOptions.Layout.PLAYLIST) continue;
            layout.addItem(choice);
        }
        var initialLayout = initial.layout();
        boolean availableLayout = false;
        for (int index = 0; index < layout.getItemCount(); index++) availableLayout |= layout.getItemAt(index) == initialLayout;
        layout.setSelectedItem(availableLayout ? initialLayout : ProcessingOptions.Layout.CURRENT);
        conflict.setSelectedIndex(initial.conflictPolicy().ordinal());
        template.setText(initial.template());
        if (!online) {
            for (var checkbox : List.of(enrich, saveLyrics, embedLyrics)) {
                checkbox.setSelected(false); checkbox.setEnabled(false); checkbox.setToolTipText("登录网易云账号后可用");
            }
        }
        addSection("歌曲信息");
        add(pair(tags, cover)); add(row(enrich)); add(labeled("已有内容", tagPolicy));
        addSection("歌词");
        add(pair(saveLyrics, embedLyrics)); add(pair(translation, romanization));
        addSection("保存设置");
        var directoryRow = new JPanel(new BorderLayout(8, 0)); directoryRow.setOpaque(false);
        directoryRow.add(directory, BorderLayout.CENTER);
        var browse = UiStyle.button("选择目录"); browse.setIcon(UIManager.getIcon("FileView.directoryIcon"));
        browse.addActionListener(event -> {
            Path current;
            try { current = directory(); } catch (RuntimeException exception) { current = destination; }
            var chooser = new JFileChooser(com.ncmbreaker.app.AppSettingsStore.existingDirectory(current));
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) directory.setText(chooser.getSelectedFile().getAbsolutePath());
        });
        directoryRow.add(browse, BorderLayout.EAST);
        add(labeled("保存到", directoryRow));
        add(pair(labeled("目录与文件名", layout), labeled("文件重名时", conflict)));
        templateRow = labeled("命名模板", template); add(templateRow);
        template.setToolTipText("{artist} {albumArtist} {album} {title} {track} {disc} {index} {playlist} {id} {quality} {format}");
        add(row(exportCover));
        coverModeRow = labeled("封面文件名", coverMode); add(coverModeRow);
        add(row(playlist));
        addSection("预计输出");
        preview.setFocusable(true);
        var previewScroll = UiStyle.scrollPane(preview);
        previewScroll.setPreferredSize(new Dimension(0, 94));
        previewScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 110));
        add(previewScroll);
        var all = UiStyle.button("查看全部文件");
        all.addActionListener(event -> showAll());
        add(row(all));
        problem.setForeground(UiStyle.muted()); problem.putClientProperty("html.disable", Boolean.TRUE);
        add(row(problem));
        for (var checkbox : List.of(tags, cover, enrich, saveLyrics, embedLyrics, translation, romanization, exportCover, playlist)) checkbox.addActionListener(event -> changed());
        enrich.addActionListener(event -> refreshMetadata());
        for (var combo : List.of(tagPolicy, coverMode, layout, conflict)) { UiStyle.comboBox(combo); combo.addActionListener(event -> changed()); }
        var listener = new DocumentListener() {
            public void insertUpdate(DocumentEvent event) { changed(); }
            public void removeUpdate(DocumentEvent event) { changed(); }
            public void changedUpdate(DocumentEvent event) { changed(); }
        };
        directory.getDocument().addDocumentListener(listener); template.getDocument().addDocumentListener(listener);
        initializing = false; changed();
    }

    public void addChangeListener(Runnable listener) { listeners.add(listener); }
    public void setQuality(String quality, String format) { this.quality = quality; this.downloadFormat = format; changed(); }
    public void setItems(List<PreviewItem> items) {
        var next = List.copyOf(items);
        if (originalItems.equals(next)) return;
        originalItems = next; this.items = next; refreshMetadata(); changed();
    }
    public List<PreviewItem> items() { return List.copyOf(items); }
    public void setPreparationService(AudioProcessingService service) {
        if (preparation == service) return;
        preparation = service; refreshMetadata();
    }
    public ProcessingOptions preferences() { return options().withPlaylist(playlist.isSelected()); }
    public void close() {
        metadataGeneration++;
        if (metadataCancellation != null) metadataCancellation.cancel();
        if (metadataWorker != null) metadataWorker.cancel(true);
        metadataWorker = null;
    }
    public void setBusy(boolean busy) {
        if (busy) rememberEnabled(this);
        else {
            enabledBeforeBusy.forEach(Component::setEnabled);
            enabledBeforeBusy.clear(); changed();
        }
    }
    private void rememberEnabled(Container parent) {
        for (var component : parent.getComponents()) {
            if (component instanceof AbstractButton || component instanceof JComboBox<?> || component instanceof JTextField) {
                enabledBeforeBusy.putIfAbsent(component, component.isEnabled()); component.setEnabled(false);
            }
            if (component instanceof Container nested) rememberEnabled(nested);
        }
    }
    private void refreshMetadata() {
        close();
        metadataNotice = "";
        items = originalItems;
        if (!enrich.isSelected() || preparation == null) { changed(); return; }
        final ProcessingOptions options;
        try { options = options().metadataOnly(); } catch (RuntimeException exception) { changed(); return; }
        long generation = metadataGeneration;
        var cancellation = new DownloadCancellation(); metadataCancellation = cancellation;
        var inputs = originalItems;
        metadataWorker = new SwingWorker<>() {
            private final List<String> warnings = new ArrayList<>();
            @Override protected List<PreviewItem> doInBackground() throws Exception {
                var result = new ArrayList<PreviewItem>();
                preparation.prefetchMetadata(inputs.stream().map(PreviewItem::metadata).toList(), cancellation);
                for (var item : inputs) {
                    cancellation.check();
                    var prepared = preparation.prepare(item.metadata(), options, cancellation);
                    prepared.warnings().forEach(warning -> warnings.add(warning.message()));
                    result.add(new PreviewItem(prepared.metadata(), item.source(), item.inputRoot(), item.format(), item.index()));
                }
                return List.copyOf(result);
            }
            @Override protected void done() {
                if (generation != metadataGeneration) return;
                metadataWorker = null;
                try {
                    items = get();
                    metadataNotice = warnings.isEmpty() ? "歌曲资料已补全" : "部分歌曲资料未取得";
                    problem.setToolTipText(warnings.isEmpty() ? null : String.join("；", warnings.stream().distinct().toList()));
                } catch (java.util.concurrent.CancellationException ignored) { }
                catch (Exception exception) { metadataNotice = "歌曲资料未补全"; problem.setToolTipText(exception.getMessage()); }
                changed();
            }
        };
        metadataWorker.execute(); changed();
    }
    public Path directory() {
        if (directory.getText().isBlank()) throw new IllegalArgumentException("请选择保存目录");
        return Path.of(directory.getText().strip()).toAbsolutePath().normalize();
    }
    public ProcessingOptions options() {
        var value = new ProcessingOptions(tags.isSelected(), cover.isSelected(), enrich.isSelected(), tagPolicy.getSelectedIndex() == 1,
                saveLyrics.isSelected(), embedLyrics.isSelected(), translation.isSelected(), romanization.isSelected(),
                exportCover.isSelected(), coverMode.getSelectedIndex() == 1, playlist.isVisible() && playlist.isSelected(),
                (ProcessingOptions.Layout) layout.getSelectedItem(), template.getText(), NcmFileIO.ConflictPolicy.values()[conflict.getSelectedIndex()]);
        OutputPlanner.validate(value);
        return value;
    }
    public String validation() {
        if (metadataWorker != null) return "正在补全歌曲资料";
        try { directory(); options(); return ""; } catch (RuntimeException exception) { return exception.getMessage(); }
    }
    private List<String> paths(boolean absolute) {
        var options = options();
        var result = new ArrayList<String>();
        var planned = new java.util.HashSet<Path>();
        var companions = new ArrayList<String>();
        if (options.saveLyrics()) companions.add(".lrc");
        if (options.exportCover() && !options.albumCover()) { companions.add(".jpg"); companions.add(".png"); }
        for (var item : items) {
            var context = new OutputPlanner.Context(directory(), collection, item.index(), item.source(), item.inputRoot());
            var desired = OutputPlanner.path(context, item.metadata(), quality, conversion ? item.format() : downloadFormat, options);
            var path = OutputPlanner.previewPath(desired, options.conflictPolicy(), planned, companions);
            result.add((absolute ? path : context.directory().relativize(path))
                    + (options.conflictPolicy() == NcmFileIO.ConflictPolicy.SKIP && java.nio.file.Files.exists(path) ? "（已存在，将跳过）" : ""));
            if (options.saveLyrics()) {
                var lyric = OutputPlanner.companion(path, ".lrc");
                result.add("  " + (absolute ? lyric : context.directory().relativize(lyric)));
            }
        }
        if (options.playlist()) {
            var name = OutputPlanner.safeName(collection.isBlank() ? "歌单" : collection) + ".m3u8";
            result.add(absolute ? directory().resolve(name).toString() : name);
        }
        return result;
    }
    private void changed() {
        if (initializing) return;
        boolean lyrics = saveLyrics.isSelected() || embedLyrics.isSelected();
        translation.setEnabled(lyrics); romanization.setEnabled(lyrics);
        tagPolicy.setEnabled(tags.isSelected() || cover.isSelected() || embedLyrics.isSelected());
        coverMode.setEnabled(exportCover.isSelected());
        coverModeRow.setVisible(exportCover.isSelected());
        templateRow.setVisible(layout.getSelectedItem() == ProcessingOptions.Layout.CUSTOM);
        try {
            var paths = paths(false);
            preview.setText(String.join("\n", paths.stream().limit(3).toList()) + (paths.size() > 3 ? "\n另有 " + (paths.size() - 3) + " 个输出路径" : ""));
            problem.setText(metadataWorker != null ? "正在补全歌曲资料" : metadataNotice.isBlank() ? " " : metadataNotice);
        } catch (RuntimeException exception) { preview.setText(""); problem.setText(exception.getMessage()); }
        preview.setCaretPosition(0);
        revalidate();
        listeners.forEach(Runnable::run);
    }
    private void showAll() {
        try {
            var area = text(12); area.setText(String.join("\n\n", paths(true))); area.setFocusable(true);
            var scroll = UiStyle.scrollPane(area); scroll.setPreferredSize(new Dimension(620, 340));
            JOptionPane.showMessageDialog(this, scroll, "预计输出文件", JOptionPane.PLAIN_MESSAGE);
        } catch (RuntimeException exception) { JOptionPane.showMessageDialog(this, exception.getMessage(), "输出设置", JOptionPane.WARNING_MESSAGE); }
    }
    private void addSection(String title) {
        var label = new JLabel(title); label.setFont(label.getFont().deriveFont(Font.BOLD));
        var panel = row(label); panel.setBorder(BorderFactory.createEmptyBorder(13, 0, 5, 0)); add(panel);
    }
    private static JCheckBox check(String title) { var check = new JCheckBox(title); check.setOpaque(false); return check; }
    private static JPanel row(JComponent component) {
        var panel = new JPanel(new BorderLayout()); panel.setOpaque(false); panel.add(component);
        panel.setAlignmentX(LEFT_ALIGNMENT); panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44)); return panel;
    }
    private static JPanel pair(JComponent first, JComponent second) {
        var panel = new JPanel(new GridLayout(1, 2, 14, 0)); panel.setOpaque(false); panel.add(first); panel.add(second);
        panel.setAlignmentX(LEFT_ALIGNMENT); panel.setBorder(BorderFactory.createEmptyBorder(3, 0, 3, 0));
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 70)); return panel;
    }
    private static JPanel labeled(String text, JComponent field) {
        var panel = new JPanel(new BorderLayout(0, 5)); panel.setOpaque(false);
        var label = new JLabel(text); label.setForeground(UiStyle.muted()); label.setLabelFor(field);
        panel.add(label, BorderLayout.NORTH); panel.add(field, BorderLayout.CENTER);
        panel.setAlignmentX(LEFT_ALIGNMENT); panel.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 70)); return panel;
    }
    private static JTextArea text(int rows) {
        var text = new JTextArea(rows, 0); text.setEditable(false); text.setLineWrap(true); text.setWrapStyleWord(true);
        text.setFont(UIManager.getFont("Label.font")); text.setOpaque(false); return text;
    }

    @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
    @Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return 18; }
    @Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
        return Math.max(18, (orientation == SwingConstants.VERTICAL ? visible.height : visible.width) - 18);
    }
    @Override public boolean getScrollableTracksViewportWidth() { return true; }
    @Override public boolean getScrollableTracksViewportHeight() { return false; }
}
