package com.ncmbreaker.ui.music;

import com.ncmbreaker.download.AudioDownloader;
import com.ncmbreaker.download.DownloadCancellation;
import com.ncmbreaker.netease.auth.LoginSession;
import com.ncmbreaker.netease.music.AudioQuality;
import com.ncmbreaker.netease.music.MusicException;
import com.ncmbreaker.netease.music.MusicModels.Song;
import com.ncmbreaker.netease.music.MusicModels.PlaylistContent;
import com.ncmbreaker.netease.music.MusicService;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

final class DownloadQueue extends AbstractTableModel implements AutoCloseable {
    private static final int MAX_CONCURRENT_DOWNLOADS = 3;
    private static final String[] COLUMNS = {"歌曲", "音质", "进度", "状态"};
    private final List<Task> tasks = new ArrayList<>();
    private final Map<TaskKey, Task> pending = new HashMap<>();
    private final AtomicInteger workerIds = new AtomicInteger();
    private final ExecutorService executor = Executors.newFixedThreadPool(MAX_CONCURRENT_DOWNLOADS,
            task -> new Thread(task, "music-download-" + workerIds.incrementAndGet()));
    private volatile boolean closed;
    private Runnable successListener = () -> { };
    private Consumer<BatchResult> batchListener = result -> { };

    enum State {
        WAITING, RUNNING, SUCCEEDED, FAILED, CANCELLED;
        boolean finished() { return this == SUCCEEDED || this == FAILED || this == CANCELLED; }
    }

    private record TaskKey(LoginSession session, long songId, AudioQuality quality, Path directory) { }

    static final class Batch {
        final LoginSession session;
        final PlaylistContent source;
        final PlaylistContent original;
        final List<Task> tasks = new ArrayList<>();
        int remaining;
        int skipped;
        Batch(LoginSession session, PlaylistContent source, PlaylistContent original) {
            this.session = session; this.source = source; this.original = original;
        }
    }

    record TaskResult(Song song, State state, String message) { }
    record BatchResult(LoginSession session, PlaylistContent source, PlaylistContent original,
                       List<TaskResult> tasks, int skipped) {
        BatchResult { tasks = List.copyOf(tasks); }
        long count(State state) { return tasks.stream().filter(task -> task.state() == state).count(); }
        List<Song> failedSongs() {
            return tasks.stream().filter(task -> task.state() == State.FAILED).map(TaskResult::song).toList();
        }
    }

    static final class Task {
        final LoginSession session;
        Song song;
        final AudioQuality quality;
        final Path directory;
        final boolean tags;
        final boolean cover;
        final DownloadCancellation cancellation = new DownloadCancellation();
        String status = "等待下载";
        String progress = "";
        Path output;
        State state = State.WAITING;
        volatile boolean started;
        Future<?> future;
        final Batch batch;
        final TaskKey key;
        Task(LoginSession session, Song song, AudioQuality quality, Path directory, boolean tags, boolean cover, Batch batch) {
            this.session = session; this.song = song; this.quality = quality; this.directory = directory;
            this.tags = tags; this.cover = cover;
            this.batch = batch;
            this.key = new TaskKey(session, song.id(), quality, directory);
        }
        boolean finished() { return state.finished(); }
    }

    boolean add(LoginSession session, Song song, AudioQuality quality, Path directory, boolean tags, boolean cover) {
        var task = create(session, song, quality, directory, tags, cover, null);
        if (task == null) return false;
        fireTableRowsInserted(tasks.size() - 1, tasks.size() - 1);
        task.future = executor.submit(() -> run(task));
        return true;
    }

    void addBatch(Batch batch, List<Song> songs, AudioQuality quality, Path directory, boolean tags, boolean cover) {
        if (closed) return;
        int first = tasks.size();
        var ids = new HashSet<Long>();
        for (var song : songs) {
            if (!ids.add(song.id())) continue;
            var task = create(batch.session, song, quality, directory, tags, cover, batch);
            if (task == null) batch.skipped++;
            else { batch.tasks.add(task); batch.remaining++; }
        }
        if (batch.tasks.isEmpty()) return;
        fireTableRowsInserted(first, tasks.size() - 1);
        for (var task : batch.tasks) task.future = executor.submit(() -> run(task));
    }

    private Task create(LoginSession session, Song song, AudioQuality quality, Path directory,
                        boolean tags, boolean cover, Batch batch) {
        var task = new Task(session, song, quality, directory, tags, cover, batch);
        if (closed || pending.putIfAbsent(task.key, task) != null) return null;
        tasks.add(task);
        return task;
    }

    Task task(int index) { return tasks.get(index); }
    void setSuccessListener(Runnable listener) { successListener = listener; }
    void setBatchListener(Consumer<BatchResult> listener) { batchListener = listener; }
    boolean hasPendingTasks() { return !pending.isEmpty(); }
    void cancel(int index) {
        var task = tasks.get(index);
        synchronized (task) {
            if (task.finished()) return;
            task.cancellation.cancel();
            if (task.future != null) task.future.cancel(true);
            if (!task.started) finish(task, State.CANCELLED, "已取消", null);
            else { task.status = "正在取消"; fireTableRowsUpdated(index, index); }
        }
    }
    void cancelBatch(Batch batch) {
        for (int i = 0; i < tasks.size(); i++) if (tasks.get(i).batch == batch) cancel(i);
    }
    void cancelSession(LoginSession session) {
        for (int i = 0; i < tasks.size(); i++) if (tasks.get(i).session == session) cancel(i);
    }
    void clearFinished() { tasks.removeIf(Task::finished); fireTableDataChanged(); }
    private void run(Task task) {
        synchronized (task) {
            if (task.cancellation.isCancelled()) return;
            task.started = true;
        }
        try {
            task.cancellation.check();
            var service = new MusicService(task.session);
            var song = task.song;
            if (task.batch != null && (!song.detailAvailable() || song.coverUrl().isBlank())) {
                update(task, "正在获取歌曲信息", "");
                var details = service.songs(List.of(song.id()), count -> { });
                task.cancellation.check();
                if (!details.isEmpty() && details.get(0).detailAvailable()) song = details.get(0);
                if (!song.detailAvailable()) throw new MusicException("歌曲详情暂不可用，请稍后重试");
                var resolved = song;
                SwingUtilities.invokeLater(() -> task.song = resolved);
            }
            update(task, "正在获取下载地址", "");
            var authorization = service.authorize(song.id(), task.quality);
            task.cancellation.check();
            if (authorization.actual() != task.quality) {
                throw new MusicException("当前返回" + authorization.actual() + "，请重新选择音质");
            }
            long start = System.nanoTime();
            var result = new AudioDownloader().download(authorization, song, task.directory, task.tags, task.cover, task.cancellation,
                    (done, total) -> {
                        double seconds = Math.max(0.1, (System.nanoTime() - start) / 1_000_000_000.0);
                        var progress = "%.1f%% · %.1f MB / %.1f MB · %.1f MB/s".formatted(
                                done * 100.0 / total, done / 1048576.0, total / 1048576.0, done / 1048576.0 / seconds);
                        update(task, done == total ? "正在校验" : "正在下载", progress);
                    }, stage -> update(task, stage, "100%"));
            var status = "完成 · " + result.format().toUpperCase(java.util.Locale.ROOT);
            if (!result.warning().isBlank()) status += " · " + result.warning();
            complete(task, State.SUCCEEDED, status, result.file());
        } catch (CancellationException exception) {
            complete(task, State.CANCELLED, "已取消", null);
        } catch (Exception exception) {
            try { task.cancellation.check(); }
            catch (CancellationException cancelled) { complete(task, State.CANCELLED, "已取消", null); return; }
            complete(task, State.FAILED, MusicException.describe(exception), null);
        }
    }

    private void update(Task task, String status, String progress) {
        SwingUtilities.invokeLater(() -> {
            if (task.finished() || task.cancellation.isCancelled()) return;
            task.state = State.RUNNING;
            task.status = status; task.progress = progress;
            int row = tasks.indexOf(task);
            if (row >= 0) fireTableRowsUpdated(row, row);
        });
    }

    private void complete(Task task, State state, String status, Path output) {
        SwingUtilities.invokeLater(() -> finish(task, state, status, output));
    }

    private void finish(Task task, State state, String status, Path output) {
        if (task.finished()) return;
        task.state = state; task.status = status; task.output = output;
        task.progress = state == State.SUCCEEDED ? "100%" : "";
        pending.remove(task.key, task);
        int row = tasks.indexOf(task);
        if (row >= 0) fireTableRowsUpdated(row, row);
        if (!closed && state == State.SUCCEEDED) successListener.run();
        var batch = task.batch;
        if (batch != null && --batch.remaining == 0 && !closed) {
            var result = new BatchResult(batch.session, batch.source, batch.original,
                    batch.tasks.stream().map(item -> new TaskResult(item.song, item.state, item.status)).toList(), batch.skipped);
            SwingUtilities.invokeLater(() -> { if (!closed) batchListener.accept(result); });
        }
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
