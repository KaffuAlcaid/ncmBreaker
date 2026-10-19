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
    BatchDownloadDialog(Window owner, PlaylistContent source, List<Song> songs, Consumer<AudioQuality> onDownload) {
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
        quality.setSelectedItem(AudioQuality.LOSSLESS);
        quality.setPreferredSize(new Dimension(0, 38));
        UiStyle.comboBox(quality);
        qualityPanel.add(quality, BorderLayout.CENTER);
        root.add(qualityPanel, BorderLayout.CENTER);
        var actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        var cancel = UiStyle.button("取消");
        cancel.addActionListener(event -> dispose());
        var download = UiStyle.button("开始下载");
        download.setName("batch.start");
        download.addActionListener(event -> {
            download.setEnabled(false);
            dispose();
            onDownload.accept((AudioQuality) quality.getSelectedItem());
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
