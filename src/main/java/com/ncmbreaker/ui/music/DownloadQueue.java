package com.ncmbreaker.ui.music;

import com.ncmbreaker.audio.*;
import com.ncmbreaker.download.DownloadManager;
import com.ncmbreaker.download.DownloadManager.*;
import com.ncmbreaker.netease.auth.LoginSession;
import com.ncmbreaker.netease.music.AudioQuality;
import com.ncmbreaker.netease.music.MusicModels.Song;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

final class DownloadQueue extends AbstractTableModel implements AutoCloseable {
    private static final String[] COLUMNS = {"歌曲", "音质", "来源", "进度", "状态"};
    final DownloadManager manager;
    private List<Task> rows;
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private boolean closed;

    DownloadQueue() { this(new DownloadManager()); }
    DownloadQueue(DownloadManager manager) {
        this.manager = manager;
        rows = manager.tasks();
        manager.setListener(() -> {
            if (!refreshing.compareAndSet(false, true)) return;
            SwingUtilities.invokeLater(() -> {
                refreshing.set(false);
                if (closed) return;
                var updated = manager.tasks();
                boolean same = rows.equals(updated);
                rows = updated;
                if (same && !rows.isEmpty()) fireTableRowsUpdated(0, rows.size() - 1);
                else fireTableDataChanged();
            });
        });
    }

    boolean add(LoginSession session, Song song, AudioQuality quality, OutputPlanner.Context context, ProcessingOptions options,
                TrackMetadata metadata, AudioProcessingService processing) {
        return manager.add(session, song, quality, context, options, metadata, processing);
    }
    void addBatch(Batch batch, List<Song> songs, AudioQuality quality) { manager.addBatch(batch, songs, quality); }
    Task task(int index) { return rows.get(index); }
    List<Task> tasks() { return rows; }
    void setSuccessListener(Runnable listener) { manager.setSuccessListener(() -> SwingUtilities.invokeLater(listener)); }
    void setBatchListener(Consumer<BatchResult> listener) {
        manager.setBatchListener(result -> SwingUtilities.invokeLater(() -> { if (!closed) listener.accept(result); }));
    }
    boolean hasPendingTasks() { return manager.hasPendingTasks(); }
    void clearFinished() { manager.clearFinished(); }
    @Override public void close() { closed = true; manager.close(); }
    @Override public int getRowCount() { return rows.size(); }
    @Override public int getColumnCount() { return COLUMNS.length; }
    @Override public String getColumnName(int column) { return COLUMNS[column]; }
    @Override public Object getValueAt(int row, int column) {
        var task = rows.get(row);
        return switch (column) {
            case 0 -> task.song.title();
            case 1 -> task.quality.toString();
            case 2 -> task.sourceName();
            case 3 -> task.progress;
            default -> task.status;
        };
    }
}
