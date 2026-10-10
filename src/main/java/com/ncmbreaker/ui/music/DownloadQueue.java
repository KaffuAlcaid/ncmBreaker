package com.ncmbreaker.ui.music;

import com.ncmbreaker.download.AudioDownloader;
import com.ncmbreaker.download.DownloadCancellation;
import com.ncmbreaker.netease.auth.LoginSession;
import com.ncmbreaker.netease.music.AudioQuality;
import com.ncmbreaker.netease.music.MusicException;
import com.ncmbreaker.netease.music.MusicModels.Song;
import com.ncmbreaker.netease.music.MusicService;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

final class DownloadQueue extends AbstractTableModel implements AutoCloseable {
    private static final String[] COLUMNS = {"歌曲", "音质", "进度", "状态"};
    private final List<Task> tasks = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> new Thread(task, "music-download"));
    private volatile boolean closed;

    static final class Task {
        final LoginSession session;
        final Song song;
        final AudioQuality quality;
        final Path directory;
        final boolean tags;
        final boolean cover;
        final DownloadCancellation cancellation = new DownloadCancellation();
        String status = "等待下载";
        String progress = "";
        Path output;
        boolean finished;
        volatile boolean started;
        Future<?> future;
        Task(LoginSession session, Song song, AudioQuality quality, Path directory, boolean tags, boolean cover) {
            this.session = session; this.song = song; this.quality = quality; this.directory = directory;
            this.tags = tags; this.cover = cover;
        }
    }

    boolean add(LoginSession session, Song song, AudioQuality quality, Path directory, boolean tags, boolean cover) {
        if (closed) return false;
        boolean duplicate = tasks.stream().anyMatch(task -> !task.finished && task.session == session
                && task.song.id() == song.id() && task.quality == quality && task.directory.equals(directory));
        if (duplicate) return false;
        var task = new Task(session, song, quality, directory, tags, cover);
        tasks.add(task);
        fireTableRowsInserted(tasks.size() - 1, tasks.size() - 1);
        task.future = executor.submit(() -> run(task));
        return true;
    }

    Task task(int index) { return tasks.get(index); }
    void cancel(int index) {
        var task = tasks.get(index);
        if (!task.finished) {
            task.cancellation.cancel();
            task.future.cancel(true);
            task.finished = !task.started;
            task.status = task.finished ? "已取消" : "正在取消";
            fireTableRowsUpdated(index, index);
        }
    }
    void cancelSession(LoginSession session) {
        for (int i = 0; i < tasks.size(); i++) if (tasks.get(i).session == session) cancel(i);
    }
    void clearFinished() { tasks.removeIf(task -> task.finished); fireTableDataChanged(); }
    private void run(Task task) {
        task.started = true;
        try {
            task.cancellation.check();
            update(task, "正在获取下载地址", "", false, null);
            var authorization = new MusicService(task.session).authorize(task.song.id(), task.quality);
            task.cancellation.check();
            if (authorization.actual() != task.quality) {
                throw new MusicException("当前返回" + authorization.actual() + "，请重新选择音质。");
            }
            long start = System.nanoTime();
            var result = new AudioDownloader().download(authorization, task.song, task.directory, task.tags, task.cover, task.cancellation,
                    (done, total) -> {
                        double seconds = Math.max(0.1, (System.nanoTime() - start) / 1_000_000_000.0);
                        var progress = "%.1f%% · %.1f MB / %.1f MB · %.1f MB/s".formatted(
                                done * 100.0 / total, done / 1048576.0, total / 1048576.0, done / 1048576.0 / seconds);
                        update(task, done == total ? "正在校验" : "正在下载", progress, false, null);
                    }, stage -> update(task, stage, "100%", false, null));
            var status = "完成 · " + result.format().toUpperCase(java.util.Locale.ROOT);
            if (!result.warning().isBlank()) status += " · " + result.warning();
            update(task, status, "100%", true, result.file());
        } catch (CancellationException exception) {
            update(task, "已取消", "", true, null);
        } catch (Exception exception) {
            try { task.cancellation.check(); }
            catch (CancellationException cancelled) { update(task, "已取消", "", true, null); return; }
            update(task, MusicException.describe(exception), "", true, null);
        }
    }

    private void update(Task task, String status, String progress, boolean finished, Path output) {
        SwingUtilities.invokeLater(() -> {
            if (!finished && task.cancellation.isCancelled()) return;
            task.status = status; task.progress = progress; task.finished = finished; task.output = output;
            int row = tasks.indexOf(task);
            if (row >= 0) fireTableRowsUpdated(row, row);
        });
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        for (int index = 0; index < tasks.size(); index++) cancel(index);
        executor.shutdownNow();
    }
    @Override public int getRowCount() { return tasks.size(); }
    @Override public int getColumnCount() { return COLUMNS.length; }
    @Override public String getColumnName(int column) { return COLUMNS[column]; }
    @Override public Object getValueAt(int row, int column) {
        var task = tasks.get(row);
        return switch (column) {
            case 0 -> task.song.title(); case 1 -> task.quality.toString();
            case 2 -> task.progress; default -> task.status;
        };
    }
}
