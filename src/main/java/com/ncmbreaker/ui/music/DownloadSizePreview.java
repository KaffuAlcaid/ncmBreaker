package com.ncmbreaker.ui.music;

import com.ncmbreaker.app.FileSize;
import com.ncmbreaker.netease.auth.LoginSession;
import com.ncmbreaker.netease.music.AudioQuality;
import com.ncmbreaker.netease.music.MusicException;
import com.ncmbreaker.netease.music.MusicModels.QualityOption;
import com.ncmbreaker.netease.music.MusicModels.Song;
import com.ncmbreaker.netease.music.MusicService;
import com.ncmbreaker.ui.UiStyle;
import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.*;

final class DownloadSizePreview extends JPanel implements AutoCloseable {
    private record Update(long id, List<QualityOption> options, boolean complete) { }
    private final MusicService service;
    private final List<Song> songs;
    private final Map<Long, List<QualityOption>> cache = new HashMap<>();
    private final Set<Long> complete = new HashSet<>();
    private final List<Runnable> listeners = new ArrayList<>();
    private final JTextArea total = new JTextArea("所选音频大小：正在查询", 2, 0);
    private final JLabel status = new JLabel(" ");
    private final JButton refresh = UiStyle.button("刷新大小");
    private SwingWorker<Void, Update> worker;
    private AudioQuality quality;
    private long generation;
    private boolean closed;

    DownloadSizePreview(LoginSession session, List<Song> songs) {
        super(new BorderLayout(12, 0));
        this.service = new MusicService(session); this.songs = List.copyOf(songs);
        var text = new JPanel(new BorderLayout(0, 3));
        total.setName("batch.totalSize"); status.setName("batch.sizeStatus");
        total.setEditable(false); total.setFocusable(false); total.setOpaque(false);
        total.setLineWrap(true); total.setWrapStyleWord(true); total.setFont(UIManager.getFont("Label.font"));
        status.putClientProperty("html.disable", true);
        status.setForeground(UiStyle.muted()); text.add(total, BorderLayout.CENTER); text.add(status, BorderLayout.SOUTH); add(text, BorderLayout.CENTER);
        refresh.setName("batch.refreshSize"); refresh.setPreferredSize(new Dimension(96, 34));
        refresh.addActionListener(event -> { cache.clear(); complete.clear(); load(); });
        var actions = new JPanel(new GridBagLayout()); actions.add(refresh); add(actions, BorderLayout.EAST);
    }

    void setQuality(AudioQuality quality) {
        if (this.quality == quality) return;
        this.quality = quality; load();
    }
    void addChangeListener(Runnable listener) { listeners.add(listener); }
    void removeChangeListener(Runnable listener) { listeners.remove(listener); }
    Map<Long, Long> sizes() {
        var values = new HashMap<Long, Long>();
        cache.forEach((id, options) -> options.stream().filter(option -> option.quality() == quality && option.bytes() > 0)
                .findFirst().ifPresent(option -> values.put(id, option.bytes())));
        return Map.copyOf(values);
    }

    static String summary(List<Song> songs, Map<Long, Long> sizes) {
        long bytes = 0; int unknown = 0;
        for (long id : songs.stream().map(Song::id).distinct().toList()) {
            long size = sizes.getOrDefault(id, 0L);
            if (size > 0) bytes = Math.addExact(bytes, size); else unknown++;
        }
        if (unknown == 0) return FileSize.format(bytes);
        return bytes == 0 ? unknown + " 首歌曲大小未知" : "已知 " + FileSize.format(bytes) + "，另有 " + unknown + " 首大小未知";
    }

    private void load() {
        if (closed || quality == null) return;
        long current = ++generation;
        if (worker != null) worker.cancel(true);
        var selectedQuality = quality;
        var known = new HashMap<>(cache); var full = new HashSet<>(complete);
        var ids = songs.stream().map(Song::id).distinct().toList();
        refresh.setEnabled(false); status.setText("正在查询文件大小"); status.setToolTipText(null);
        worker = new SwingWorker<>() {
            @Override protected Void doInBackground() throws Exception {
                var missing = ids.stream().filter(id -> !known.containsKey(id)).toList();
                for (int start = 0; start < missing.size(); start += 200) {
                    if (isCancelled()) return null;
                    var values = service.qualities(missing.subList(start, Math.min(start + 200, missing.size())));
                    known.putAll(values);
                    values.forEach((id, options) -> publish(new Update(id, options, false)));
                }
                for (long id : ids) {
                    if (isCancelled()) return null;
                    if (full.contains(id) || known.getOrDefault(id, List.of()).stream().anyMatch(option -> option.quality() == selectedQuality)) continue;
                    try { publish(new Update(id, service.qualities(id), true)); }
                    catch (MusicException exception) {
                        if (MusicException.kind(exception) != MusicException.Kind.QUALITY) throw exception;
                        publish(new Update(id, known.getOrDefault(id, List.of()), true));
                    }
                }
                return null;
            }
            @Override protected void process(List<Update> updates) {
                if (closed || current != generation) return;
                for (var update : updates) { cache.put(update.id(), update.options()); if (update.complete()) complete.add(update.id()); }
                changed(); status.setText("正在查询，已取得 " + sizes().size() + " / " + ids.size() + " 首的大小");
            }
            @Override protected void done() {
                if (closed || current != generation) return;
                worker = null; refresh.setEnabled(true);
                try {
                    get();
                    status.setText(sizes().size() == ids.size() ? " " : "部分歌曲未提供所选音质的大小");
                } catch (java.util.concurrent.CancellationException ignored) { }
                catch (Exception exception) {
                    status.setText("部分大小未能取得"); status.setToolTipText(MusicException.describe(exception));
                }
                changed();
            }
        };
        changed(); worker.execute();
    }

    private void changed() {
        total.setText("所选音频大小：" + summary(songs, sizes()));
        total.setToolTipText(total.getText());
        for (var listener : List.copyOf(listeners)) listener.run();
    }
    @Override public void close() {
        closed = true; generation++;
        if (worker != null) worker.cancel(true);
        listeners.clear();
    }
}
