package com.ncmbreaker.ui.music;

import com.ncmbreaker.app.AppSettingsStore;
import com.ncmbreaker.audio.OutputPlanner;
import com.ncmbreaker.audio.AudioProcessingService;
import com.ncmbreaker.audio.ProcessingOptions;
import com.ncmbreaker.audio.TrackMetadata;
import com.ncmbreaker.audio.AudioResult;
import com.ncmbreaker.download.DownloadManager;
import com.ncmbreaker.netease.auth.LoginSession;
import com.ncmbreaker.netease.music.AudioQuality;
import com.ncmbreaker.netease.music.MusicException;
import com.ncmbreaker.netease.music.MusicModels.*;
import com.ncmbreaker.netease.music.MusicService;
import com.ncmbreaker.ui.ProcessingOptionsPanel;
import com.ncmbreaker.ui.UiStyle;
import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

final class QualityDialog extends JDialog {
    record Selection(Song song, AudioQuality quality, Path directory, ProcessingOptions options,
                     TrackMetadata metadata, AudioProcessingService processing, OutputPlanner.Context context, AudioResult existing) { }
    private final LoginSession session;
    private final AppSettingsStore settings;
    private final AudioProcessingService preparation;
    private final OutputPlanner.Context context;
    private Song song;
    private final JComboBox<QualityOption> quality = new JComboBox<>();
    private final JLabel status = new JLabel("正在查询音质");
    private final JButton download = UiStyle.button("下载");
    private final JButton retry = UiStyle.button("重试");
    private final ProcessingOptionsPanel processing;
    private SwingWorker<?, ?> worker;
    private final Consumer<Selection> onDownload;
    private final DownloadManager manager;

    QualityDialog(Window owner, LoginSession session, Song song, OutputPlanner.Context context,
                  ProcessingOptions initial, AppSettingsStore settings,
                  DownloadManager manager, Consumer<Selection> onDownload) {
        super(owner, "确认下载", ModalityType.APPLICATION_MODAL);
        this.session = session; this.song = song; this.settings = settings; this.onDownload = onDownload;
        this.context = context;
        this.manager = manager;
        preparation = new AudioProcessingService(session);
        processing = new ProcessingOptionsPanel(initial, context.directory(), items(), context.collection(), false, true, false);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        var root = new JPanel(new BorderLayout(0, 12));
        root.setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));
        var heading = new JPanel(new BorderLayout(0, 10));
        var title = new JLabel(song.title());
        title.putClientProperty("html.disable", Boolean.TRUE);
        title.setFont(title.getFont().deriveFont(18f));
        title.setToolTipText(song.title() + "（" + song.artistText() + "）");
        heading.add(title, BorderLayout.NORTH);
        var qualityRow = new JPanel(new BorderLayout(12, 0));
        qualityRow.add(new JLabel("下载音质"), BorderLayout.WEST);
        UiStyle.comboBox(quality);
        quality.setRenderer((list, value, index, selected, focused) -> {
            var label = new JLabel(value == null ? "" : value.quality() + "：%.1f MB".formatted(value.bytes() / 1048576.0)
                    + (value.sampleRate() > 0 ? "，%.1f kHz".formatted(value.sampleRate() / 1000.0) : ""));
            label.setOpaque(true); label.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
            label.setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            label.setForeground(selected ? list.getSelectionForeground() : list.getForeground());
            return label;
        });
        qualityRow.add(quality, BorderLayout.CENTER);
        heading.add(qualityRow, BorderLayout.CENTER); root.add(heading, BorderLayout.NORTH);
        var scroll = UiStyle.scrollPane(processing);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        root.add(scroll, BorderLayout.CENTER);
        var footer = new JPanel(new BorderLayout(8, 8));
        status.putClientProperty("html.disable", Boolean.TRUE); status.setForeground(UiStyle.muted());
        footer.add(status, BorderLayout.NORTH);
        var actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        var cancel = UiStyle.button("取消"); cancel.addActionListener(event -> dispose());
        download.addActionListener(event -> authorize()); retry.addActionListener(event -> load());
        actions.add(retry); actions.add(cancel); actions.add(download); footer.add(actions, BorderLayout.SOUTH);
        root.add(footer, BorderLayout.SOUTH); setContentPane(root);
        quality.addActionListener(event -> updateQuality());
        processing.addChangeListener(() -> download.setEnabled(worker == null && quality.getSelectedItem() != null && processing.validation().isBlank()));
        setMinimumSize(new Dimension(560, 480));
        var available = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        setSize(Math.min(680, available.width - 40), Math.min(800, available.height - 60));
        setLocationRelativeTo(owner); getRootPane().setDefaultButton(download);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent event) { processing.close(); if (worker != null) worker.cancel(true); }
        });
        load();
    }

    private List<ProcessingOptionsPanel.PreviewItem> items() {
        return List.of(new ProcessingOptionsPanel.PreviewItem(TrackMetadata.fromSong(song), null, null, "flac", context.index()));
    }
    private void updateQuality() {
        var selected = (QualityOption) quality.getSelectedItem();
        if (selected != null) processing.setQuality(selected.quality().toString(), previewFormat(selected.quality()));
    }
    static String previewFormat(AudioQuality quality) {
        return switch (quality) {
            case STANDARD, EXHIGH -> "mp3";
            case SKY, VIVID -> "待确认";
            default -> "flac";
        };
    }
    private void load() {
        quality.removeAllItems(); status.setText("正在查询音质");
        retry.setVisible(false); download.setEnabled(false);
        worker = new SwingWorker<List<QualityOption>, Void>() {
            @Override protected List<QualityOption> doInBackground() throws Exception {
                var service = new MusicService(session);
                if (!song.detailAvailable() || song.coverUrl().isBlank()) {
                    var details = service.songs(List.of(song.id()), count -> { });
                    if (!details.isEmpty() && details.get(0).detailAvailable()) song = details.get(0);
                }
                return service.qualities(song.id());
            }
            @Override protected void done() {
                if (!isDisplayable()) return;
                worker = null;
                try {
                    var available = get();
                    processing.setItems(items());
                    processing.setPreparationService(preparation);
                    for (var item : available) quality.addItem(item);
                    var preferred = available.stream().filter(item -> item.quality() == settings.batchQuality()).findFirst()
                            .orElseGet(() -> available.stream().filter(item -> item.quality() == AudioQuality.LOSSLESS).findFirst().orElse(available.get(0)));
                    quality.setSelectedItem(preferred);
                    updateQuality(); status.setText(" "); download.setEnabled(processing.validation().isBlank());
                } catch (CancellationException ignored) { }
                catch (Exception exception) { failed(exception); retry.setVisible(true); }
            }
        };
        worker.execute();
    }
    private void authorize() {
        var selected = (QualityOption) quality.getSelectedItem();
        if (selected == null) return;
        final ProcessingOptions options;
        final Path directory;
        final ProcessingOptions preferences;
        final TrackMetadata metadata;
        try { options = processing.options(); preferences = processing.preferences(); directory = processing.directory(); metadata = processing.items().get(0).metadata(); }
        catch (RuntimeException exception) { failed(exception); return; }
        retry.setVisible(false); download.setEnabled(false); quality.setEnabled(false);
        processing.setBusy(true);
        status.setText("正在确认下载权限与保存目录");
        worker = new SwingWorker<AuthorizedDownload, Void>() {
            private DownloadManager.Comparison comparison;
            @Override protected AuthorizedDownload doInBackground() throws Exception {
                var authorization = new MusicService(session).authorize(song.id(), selected.quality());
                comparison = manager.compare(List.of(song), authorization.actual(), directory, null, session.account().userId());
                if (comparison.items().get(0).difference() != DownloadManager.Difference.PRESENT) {
                    OutputPlanner.preflight(directory, authorization.bytes(), options.needsTags() || options.exportCover());
                }
                return authorization;
            }
            @Override protected void done() {
                if (!isDisplayable()) return;
                worker = null; quality.setEnabled(true); processing.setBusy(false);
                try {
                    var result = get();
                    if (result.actual() != selected.quality() && JOptionPane.showConfirmDialog(QualityDialog.this,
                            "所选音质为" + selected.quality() + "，服务器实际提供" + result.actual() + "，是否按实际音质下载？",
                            "音质已变化", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE) != JOptionPane.YES_OPTION) {
                        load(); return;
                    }
                    AudioResult existing = null;
                    if (comparison.items().get(0).difference() != DownloadManager.Difference.NEW) {
                        var dialog = new IncrementalDownloadDialog(QualityDialog.this, comparison); dialog.setVisible(true);
                        var choice = dialog.selection();
                        if (choice == null) { download.setEnabled(processing.validation().isBlank()); status.setText(" "); return; }
                        if (choice.songs().isEmpty()) existing = choice.existing().get(song.id());
                    }
                    settings.setProcessing(false, preferences);
                    settings.setDirectory(AppSettingsStore.Directory.DOWNLOAD_OUTPUT, directory);
                    settings.setBatchPreferences(result.actual(), options.layout() == ProcessingOptions.Layout.PLAYLIST);
                    onDownload.accept(new Selection(song, result.actual(), directory, options, metadata, preparation,
                            new OutputPlanner.Context(directory, context.collection(), context.index(), null, null), existing));
                    dispose();
                } catch (CancellationException ignored) { }
                catch (Exception exception) { failed(exception); download.setEnabled(processing.validation().isBlank()); retry.setVisible(true); }
            }
        };
        worker.execute();
    }
    private void failed(Exception exception) {
        var cause = exception instanceof ExecutionException ? exception.getCause() : exception;
        var message = MusicException.describe(cause); status.setText(message); status.setToolTipText(message);
    }
}
