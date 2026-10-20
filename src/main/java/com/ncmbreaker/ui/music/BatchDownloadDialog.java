package com.ncmbreaker.ui.music;

import com.ncmbreaker.netease.music.AudioQuality;
import com.ncmbreaker.netease.music.MusicModels.PlaylistContent;
import com.ncmbreaker.netease.music.MusicModels.Song;
import com.ncmbreaker.ui.UiStyle;
import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.function.Consumer;

final class BatchDownloadDialog extends JDialog {
    record Selection(AudioQuality quality, DownloadQueue.Destination destination) { }

    BatchDownloadDialog(Window owner, PlaylistContent source, List<Song> songs, PlaylistContent original,
                        AudioQuality preferred, DownloadQueue.Destination initial, Consumer<Selection> onDownload) {
        super(owner, "批量下载", ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        var root = new JPanel(new BorderLayout(0, 18));
        root.setBorder(BorderFactory.createEmptyBorder(20, 22, 18, 22));
        var information = new JPanel(new BorderLayout(0, 12));
        var title = text(source.playlist().name());
        title.setFont(title.getFont().deriveFont(16f));
        information.add(title, BorderLayout.NORTH);
        var message = new StringBuilder("本次下载 " + songs.size() + " 首歌曲");
        if (source.expectedCount() != source.songs().size()) {
            message.append("\n歌单标记 ").append(source.expectedCount()).append(" 首，已读取 ")
                    .append(source.songs().size()).append(" 首");
        }
        long missing = songs.stream().filter(song -> !song.detailAvailable()).count();
        if (missing > 0) message.append("\n其中 ").append(missing).append(" 首详情暂不可用，将在下载时重新获取");
        information.add(text(message.toString()), BorderLayout.CENTER);
        root.add(information, BorderLayout.NORTH);
        var qualityPanel = new JPanel(new BorderLayout(12, 0));
        qualityPanel.add(new JLabel("下载音质"), BorderLayout.WEST);
        var quality = new JComboBox<>(AudioQuality.values());
        quality.setName("batch.quality");
        quality.setSelectedItem(preferred);
        quality.setPreferredSize(new Dimension(0, 38));
        UiStyle.comboBox(quality);
        qualityPanel.add(quality, BorderLayout.CENTER);
        var settings = new JPanel(new BorderLayout(0, 14));
        settings.add(qualityPanel, BorderLayout.NORTH);
        var destination = new JPanel(new BorderLayout(0, 10));
        var byPlaylist = new JCheckBox("按歌单建立子目录", initial.byPlaylist());
        byPlaylist.setOpaque(false);
        byPlaylist.setName("batch.byPlaylist");
        destination.add(byPlaylist, BorderLayout.NORTH);
        var preview = text("保存到\n" + initial.directory());
        preview.setName("batch.destination");
        preview.setRows(4);
        preview.setFocusable(true);
        preview.setToolTipText(initial.directory().toString());
        var previewScroll = UiStyle.scrollPane(preview);
        previewScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        destination.add(previewScroll, BorderLayout.CENTER);
        byPlaylist.addActionListener(event -> {
            var selected = DownloadQueue.Destination.forPlaylist(initial.baseDirectory(), byPlaylist.isSelected(), original);
            preview.setText("保存到\n" + selected.directory());
            preview.setToolTipText(selected.directory().toString());
            preview.setCaretPosition(0);
        });
        settings.add(destination, BorderLayout.CENTER);
        root.add(settings, BorderLayout.CENTER);
        var actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        var cancel = UiStyle.button("取消");
        cancel.addActionListener(event -> dispose());
        var download = UiStyle.button("开始下载");
        download.setName("batch.start");
        download.addActionListener(event -> {
            download.setEnabled(false);
            dispose();
            var selected = byPlaylist.isSelected() == initial.byPlaylist() ? initial
                    : DownloadQueue.Destination.forPlaylist(initial.baseDirectory(), byPlaylist.isSelected(), original);
            onDownload.accept(new Selection((AudioQuality) quality.getSelectedItem(), selected));
        });
        actions.add(cancel); actions.add(download);
        root.add(actions, BorderLayout.SOUTH);
        setContentPane(root);
        getRootPane().setDefaultButton(download);
        pack();
        setResizable(false);
        setLocationRelativeTo(owner);
    }

    private static JTextArea text(String value) {
        var area = new JTextArea(value, 0, 36);
        area.setEditable(false); area.setFocusable(false); area.setOpaque(false);
        area.setLineWrap(true); area.setWrapStyleWord(true);
        area.setFont(UIManager.getFont("Label.font"));
        area.setForeground(UIManager.getColor("Label.foreground"));
        return area;
    }
}
