package com.ncmbreaker.ui.music;

import com.ncmbreaker.download.DownloadManager;

import com.ncmbreaker.app.AppSettingsStore;
import com.ncmbreaker.audio.OutputPlanner;
import com.ncmbreaker.audio.AudioProcessingService;
import com.ncmbreaker.netease.auth.LoginSession;
import com.ncmbreaker.audio.ProcessingOptions;
import com.ncmbreaker.audio.TrackMetadata;
import com.ncmbreaker.netease.music.AudioQuality;
import com.ncmbreaker.netease.music.MusicModels.Song;
import com.ncmbreaker.playlist.PlaylistWorkbook;
import com.ncmbreaker.ui.ProcessingOptionsPanel;
import com.ncmbreaker.ui.UiStyle;
import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

final class BatchDownloadDialog extends JDialog {
    record Selection(AudioQuality quality, DownloadManager.Destination destination, ProcessingOptions options,
                     java.util.Map<Long, TrackMetadata> metadata, AudioProcessingService processing,
                     List<Song> songs, java.util.Map<Long, com.ncmbreaker.audio.AudioResult> existing) { }
    private SwingWorker<?, ?> worker;

    BatchDownloadDialog(Window owner, DownloadManager.Source source, List<Song> songs, List<Song> order, DownloadManager.Source original,
                        AppSettingsStore store, LoginSession session, DownloadManager.Destination initial,
                        DownloadManager manager, Consumer<Selection> onDownload) {
        super(owner, "批量下载", ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        var root = new JPanel(new BorderLayout(0, 12));
        root.setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));
        var heading = new JPanel(new BorderLayout(0, 10));
        var title = new JLabel(source.name()); title.putClientProperty("html.disable", Boolean.TRUE);
        title.setFont(title.getFont().deriveFont(18f)); title.setToolTipText(source.name());
        heading.add(title, BorderLayout.NORTH);
        var count = new JLabel("本次下载 " + songs.size() + " 首歌曲");
        long missing = songs.stream().filter(song -> !song.detailAvailable()).count();
        if (missing > 0) count.setText(count.getText() + "，" + missing + " 首详情待获取");
        if (source.expectedCount() != source.songs().size()) count.setText(count.getText() + "；已读取 " + source.songs().size() + " / " + source.expectedCount() + " 首");
        heading.add(count, BorderLayout.CENTER);
        var qualityRow = new JPanel(new BorderLayout(12, 0));
        qualityRow.add(new JLabel("下载音质"), BorderLayout.WEST);
        var quality = new JComboBox<>(AudioQuality.values()); quality.setName("batch.quality");
        quality.setSelectedItem(store.batchQuality()); UiStyle.comboBox(quality);
        qualityRow.add(quality, BorderLayout.CENTER); heading.add(qualityRow, BorderLayout.SOUTH);
        root.add(heading, BorderLayout.NORTH);
        var previews = new ArrayList<ProcessingOptionsPanel.PreviewItem>();
        var positions = new java.util.HashMap<Long, Integer>();
        for (int index = 0; index < order.size(); index++) positions.putIfAbsent(order.get(index).id(), index + 1);
        for (var song : songs) previews.add(new ProcessingOptionsPanel.PreviewItem(TrackMetadata.fromSong(song), null, null, "flac", positions.get(song.id())));
        var collection = original.playlist() == null ? original.name() : PlaylistWorkbook.baseName(original.playlist());
        var processing = new ProcessingOptionsPanel(store.processing(false), initial.baseDirectory(), previews, collection, false, true, true);
        var preparation = new AudioProcessingService(session);
        Runnable updateQuality = () -> {
            var selected = (AudioQuality) quality.getSelectedItem();
            processing.setQuality(selected.toString(), QualityDialog.previewFormat(selected));
        };
        quality.addActionListener(event -> updateQuality.run()); updateQuality.run();
        var scroll = UiStyle.scrollPane(processing); scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(18); root.add(scroll, BorderLayout.CENTER);
        var footer = new JPanel(new BorderLayout(0, 8));
        var status = new JLabel(" "); status.putClientProperty("html.disable", Boolean.TRUE);
        footer.add(status, BorderLayout.NORTH);
        var actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        var cancel = UiStyle.button("取消"); cancel.addActionListener(event -> dispose());
        var download = UiStyle.button("开始下载"); download.setName("batch.start");
        var differences = UiStyle.button("检查已下载"); differences.setName("batch.differences");
        differences.addActionListener(event -> { download.putClientProperty("showDifferences", true); download.doClick(); });
        download.addActionListener(event -> {
            final ProcessingOptions options;
            final java.nio.file.Path directory;
            final java.util.Map<Long, TrackMetadata> metadata;
            try {
                options = processing.options(); directory = processing.directory();
                metadata = processing.items().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                        item -> item.metadata().songId(), ProcessingOptionsPanel.PreviewItem::metadata, (first, second) -> first));
            }
            catch (RuntimeException exception) { status.setText(exception.getMessage()); return; }
            var selectedQuality = (AudioQuality) quality.getSelectedItem();
            boolean alwaysCompare = Boolean.TRUE.equals(download.getClientProperty("showDifferences"));
            download.putClientProperty("showDifferences", false);
            download.setEnabled(false); differences.setEnabled(false); status.setText("正在检查已有文件和保存目录");
            processing.setBusy(true); quality.setEnabled(false);
            worker = new SwingWorker<DownloadManager.Comparison, Void>() {
                @Override protected DownloadManager.Comparison doInBackground() throws Exception {
                    OutputPlanner.preflight(directory, 0, options.needsTags() || options.exportCover());
                    return manager.compare(songs, selectedQuality, directory, original, session.account().userId());
                }
                @Override protected void done() {
                    if (!isDisplayable()) return;
                    worker = null;
                    processing.setBusy(false); quality.setEnabled(true); differences.setEnabled(true);
                    try {
                        var comparison = get();
                        List<Song> selectedSongs = songs;
                        java.util.Map<Long, com.ncmbreaker.audio.AudioResult> existing = java.util.Map.of();
                        if (alwaysCompare || !comparison.removed().isEmpty()
                                || comparison.items().stream().anyMatch(item -> item.difference() != DownloadManager.Difference.NEW)) {
                            var dialog = new IncrementalDownloadDialog(BatchDownloadDialog.this, comparison);
                            dialog.setVisible(true);
                            var choice = dialog.selection();
                            if (choice == null) { download.setEnabled(processing.validation().isBlank()); status.setText(" "); return; }
                            selectedSongs = choice.songs(); existing = choice.existing();
                        }
                        store.setProcessing(false, options);
                        store.setDirectory(AppSettingsStore.Directory.DOWNLOAD_OUTPUT, directory);
                        onDownload.accept(new Selection(selectedQuality, new DownloadManager.Destination(directory,
                                options.layout() == ProcessingOptions.Layout.PLAYLIST, directory), options, metadata, preparation,
                                selectedSongs, existing));
                        dispose();
                    } catch (Exception exception) {
                        status.setText(com.ncmbreaker.netease.music.MusicException.describe(exception));
                        download.setEnabled(processing.validation().isBlank());
                    }
                }
            };
            worker.execute();
        });
        processing.addChangeListener(() -> download.setEnabled(worker == null && processing.validation().isBlank()));
        processing.setPreparationService(preparation);
        actions.add(differences); actions.add(cancel); actions.add(download); footer.add(actions, BorderLayout.SOUTH); root.add(footer, BorderLayout.SOUTH);
        setContentPane(root); getRootPane().setDefaultButton(download);
        setMinimumSize(new Dimension(560, 480));
        var available = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        setSize(Math.min(700, available.width - 40), Math.min(820, available.height - 60));
        setLocationRelativeTo(owner);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent event) { processing.close(); if (worker != null) worker.cancel(true); }
        });
    }
}
