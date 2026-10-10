package com.ncmbreaker.ui.music;

import com.ncmbreaker.netease.auth.LoginSession;
import com.ncmbreaker.ui.UiStyle;
import com.ncmbreaker.netease.music.AudioQuality;
import com.ncmbreaker.netease.music.MusicException;
import com.ncmbreaker.netease.music.MusicModels.*;
import com.ncmbreaker.netease.music.MusicService;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JTextArea;
import javax.swing.UIManager;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.function.BiConsumer;

final class QualityDialog extends JDialog {
    private final LoginSession session;
    private Song song;
    private final JPanel options = new JPanel();
    private final JTextArea status = new JTextArea("正在查询音质");
    private final JButton download = new JButton("下载");
    private final JButton retry = new JButton("重试");
    private final EnumMap<AudioQuality, JRadioButton> buttons = new EnumMap<>(AudioQuality.class);
    private SwingWorker<?, ?> worker;
    private final BiConsumer<Song, AudioQuality> onDownload;

    QualityDialog(Window owner, LoginSession session, Song song, BiConsumer<Song, AudioQuality> onDownload) {
        super(owner, "选择下载音质", ModalityType.APPLICATION_MODAL);
        this.session = session;
        this.song = song;
        this.onDownload = onDownload;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        var root = new JPanel(new BorderLayout(0, 12));
        root.setBorder(BorderFactory.createEmptyBorder(18, 20, 16, 20));
        var title = new JLabel(song.title());
        title.putClientProperty("html.disable", Boolean.TRUE);
        title.setToolTipText(song.title() + " · " + song.artistText());
        title.setFont(title.getFont().deriveFont(16f));
        var heading = new JPanel(new BorderLayout(0, 6));
        heading.add(title, BorderLayout.NORTH);
        var artist = new JLabel(song.artistText());
        artist.putClientProperty("html.disable", Boolean.TRUE);
        heading.add(artist, BorderLayout.SOUTH);
        root.add(heading, BorderLayout.NORTH);
        options.setLayout(new BoxLayout(options, BoxLayout.Y_AXIS));
        options.setPreferredSize(new Dimension(440, 304));
        root.add(options, BorderLayout.CENTER);
        var footer = new JPanel(new BorderLayout(0, 12));
        status.setEditable(false); status.setFocusable(false); status.setOpaque(false);
        status.setLineWrap(true); status.setWrapStyleWord(true);
        status.setForeground(UIManager.getColor("Label.foreground"));
        status.setFont(UIManager.getFont("Label.font"));
        status.setPreferredSize(new Dimension(440, 40));
        footer.add(status, BorderLayout.NORTH);
        var actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        var cancel = new JButton("取消");
        UiStyle.button(cancel);
        UiStyle.button(download);
        UiStyle.button(retry);
        cancel.addActionListener(event -> dispose());
        download.addActionListener(event -> authorize());
        retry.addActionListener(event -> load());
        actions.add(retry); actions.add(cancel); actions.add(download);
        footer.add(actions, BorderLayout.SOUTH);
        root.add(footer, BorderLayout.SOUTH);
        setContentPane(root);
        pack();
        setResizable(false);
        setLocationRelativeTo(owner);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent event) { if (worker != null) worker.cancel(true); }
        });
        load();
    }

    private void load() {
        options.removeAll(); buttons.clear();
        status.setText("正在查询音质"); status.setToolTipText(null);
        retry.setVisible(false); download.setEnabled(false);
        worker = new SwingWorker<List<QualityOption>, Void>() {
            @Override protected List<QualityOption> doInBackground() throws Exception {
                var service = new MusicService(session);
                if (song.coverUrl().isBlank() || !song.detailAvailable()) {
                    var details = service.songs(List.of(song.id()), value -> { });
                    if (!details.isEmpty() && details.get(0).detailAvailable()) song = details.get(0);
                }
                return service.qualities(song.id());
            }
            @Override protected void done() {
                if (!isDisplayable()) return;
                worker = null;
                try { showOptions(get()); }
                catch (CancellationException ignored) { }
                catch (Exception exception) { failed(exception); retry.setVisible(true); }
            }
        };
        worker.execute();
        options.revalidate(); options.repaint();
    }

    private void showOptions(List<QualityOption> available) {
        var group = new ButtonGroup();
        AudioQuality preferred = available.stream().anyMatch(item -> item.quality() == AudioQuality.LOSSLESS)
                ? AudioQuality.LOSSLESS : available.get(0).quality();
        for (var quality : AudioQuality.values()) {
            var resource = available.stream().filter(item -> item.quality() == quality).findFirst();
            var label = quality.toString();
            if (resource.isPresent()) {
                var item = resource.get();
                label += "  ·  %.1f MB".formatted(item.bytes() / 1048576.0);
                if (item.sampleRate() > 0) label += "  ·  %.1f kHz".formatted(item.sampleRate() / 1000.0);
            } else label += "  ·  未提供";
            var button = new JRadioButton(label);
            button.setEnabled(resource.isPresent());
            button.setSelected(quality == preferred);
            button.setMaximumSize(new Dimension(440, 37));
            button.setPreferredSize(new Dimension(440, 37));
            group.add(button); buttons.put(quality, button); options.add(button);
        }
        status.setText(" "); download.setEnabled(true);
        options.revalidate(); options.repaint();
    }

    private void authorize() {
        var selected = buttons.entrySet().stream().filter(entry -> entry.getValue().isSelected())
                .map(java.util.Map.Entry::getKey).findFirst().orElse(null);
        if (selected == null) return;
        retry.setVisible(false);
        download.setEnabled(false); buttons.values().forEach(button -> button.setEnabled(false));
        status.setText("正在确认下载权限");
        worker = new SwingWorker<AuthorizedDownload, Void>() {
            @Override protected AuthorizedDownload doInBackground() throws Exception {
                return new MusicService(session).authorize(song.id(), selected);
            }
            @Override protected void done() {
                if (!isDisplayable()) return;
                worker = null;
                try {
                    var result = get();
                    if (result.actual() != selected && JOptionPane.showConfirmDialog(QualityDialog.this,
                            "所选音质为" + selected + "，服务器实际提供" + result.actual() + "。\n是否按实际音质下载？",
                            "音质已变化", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE) != JOptionPane.YES_OPTION) {
                        load();
                        return;
                    }
                    onDownload.accept(song, result.actual());
                    dispose();
                } catch (CancellationException ignored) {
                } catch (Exception exception) {
                    failed(exception); retry.setVisible(true);
                }
            }
        };
        worker.execute();
    }

    private void failed(Exception exception) {
        Throwable cause = exception instanceof ExecutionException ? exception.getCause() : exception;
        var message = MusicException.describe(cause);
        status.setText(message); status.setToolTipText(message);
    }
}
