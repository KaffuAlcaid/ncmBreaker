package com.ncmbreaker.download;

import com.ncmbreaker.app.ApplicationPaths;
import com.ncmbreaker.audio.*;
import com.ncmbreaker.netease.auth.LoginSession;
import com.ncmbreaker.netease.music.*;
import com.ncmbreaker.netease.music.MusicModels.*;
import com.ncmbreaker.playlist.PlaylistWorkbook;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

public final class DownloadManager implements AutoCloseable {
    public enum State {
        WAITING("等待下载"), RUNNING("下载中"), PAUSED("已暂停"), RETRY_WAIT("等待重试"),
        SUCCEEDED("已完成"), SKIPPED("已跳过"), FAILED("失败"), CANCELLED("已取消");
        private final String label;
        State(String label) { this.label = label; }
        public boolean finished() { return this == SUCCEEDED || this == SKIPPED || this == FAILED || this == CANCELLED; }
        @Override public String toString() { return label; }
    }

    public record Source(String name, List<Song> songs, int expectedCount, PlaylistContent playlist) {
        public Source { songs = List.copyOf(songs); }
        public static Source forPlaylist(PlaylistContent content) {
            return new Source(content.playlist().name(), content.songs(), content.expectedCount(), content);
        }
        public static Source forSearch(SearchResult result) {
            return new Source("歌曲搜索：" + result.query(), result.songs(), result.songs().size(), null);
        }
    }

    public record Destination(Path baseDirectory, boolean byPlaylist, Path directory) {
        public static Destination forSource(Path baseDirectory, boolean byPlaylist, Source source) {
            var base = baseDirectory.toAbsolutePath().normalize();
            boolean grouped = byPlaylist && source.playlist() != null;
            return new Destination(base, grouped, grouped ? base.resolve(PlaylistWorkbook.baseName(source.playlist())) : base);
        }
    }

    public static final class Batch {
        public final String id;
        public final long accountId;
        public volatile LoginSession session;
        public final Source source;
        public final Source original;
        public final Destination destination;
        public final ProcessingOptions options;
        public final List<Song> order;
        public final Map<Long, TrackMetadata> metadata;
        public final List<Task> tasks = new CopyOnWriteArrayList<>();
        public final Map<Long, AudioResult> existing = new ConcurrentHashMap<>();
        public volatile AudioProcessingService processing;
        public volatile int remaining;
        public volatile int skipped;
        private boolean reported;
        private boolean finalizing;
        private int revision;
        private Path playlistFile;
        private String playlistWarning = "";

        public Batch(LoginSession session, Source source, Source original, Destination destination,
                     ProcessingOptions options, List<Song> order, Map<Long, TrackMetadata> metadata, AudioProcessingService processing) {
            this(UUID.randomUUID().toString(), session.account().userId(), source, original, destination, options, order, metadata);
            this.session = session;
            this.processing = processing;
        }
        private Batch(String id, long accountId, Source source, Source original, Destination destination,
                      ProcessingOptions options, List<Song> order, Map<Long, TrackMetadata> metadata) {
            this.id = id; this.accountId = accountId; this.source = source; this.original = original;
            this.destination = destination; this.options = options; this.order = List.copyOf(order); this.metadata = Map.copyOf(metadata);
        }
    }

    public record TaskResult(String id, Song song, State state, String message, AudioQuality requestedQuality,
                             AudioQuality actualQuality, ProcessingOptions options, MusicException.Kind failureKind, AudioResult output) { }
    public record BatchResult(String batchId, LoginSession session, Source source, Source original,
                              Destination destination, List<TaskResult> tasks, int skipped, int reused, Path playlistFile, String playlistWarning) {
        public BatchResult { tasks = List.copyOf(tasks); }
        public long count(State state) { return tasks.stream().filter(task -> task.state() == state).count(); }
        public List<Song> failedSongs() {
            return tasks.stream().filter(task -> task.state() == State.FAILED).map(TaskResult::song).toList();
        }
    }

    public static final class Task {
        public final String id;
        public final long accountId;
        public volatile LoginSession session;
        public volatile Song song;
        public final AudioQuality quality;
        public final Path directory;
        public final ProcessingOptions options;
        public final OutputPlanner.Context context;
        public final TrackMetadata metadata;
        public final Batch batch;
        public volatile AudioResult result;
        public volatile AudioQuality actualQuality;
        public volatile MusicException.Kind failureKind;
        public volatile String status = "等待下载";
        public volatile String progress = "";
        public volatile Path output;
        public volatile State state = State.WAITING;
        public volatile boolean started;
        public volatile int attempts;
        private AudioProcessingService processing;
        private DownloadCancellation cancellation = new DownloadCancellation();
        private Thread worker;
        private boolean ready;
        private boolean hidden;
        private boolean suspend;
        private long position;
        private long retryAt;
        private Intent intent;
        private Path temporary;

        private Task(String id, long accountId, Song song, AudioQuality quality, OutputPlanner.Context context,
                     ProcessingOptions options, Batch batch, TrackMetadata metadata) {
            this.id = id; this.accountId = accountId; this.song = song; this.quality = quality;
            this.context = context; this.directory = context.directory(); this.options = options; this.batch = batch; this.metadata = metadata;
        }
        public boolean finished() { return state.finished(); }
        public String sourceName() { return batch == null ? context.collection().isBlank() ? "单曲" : context.collection() : batch.original.name(); }
    }

    public enum Difference {
        PRESENT("已有所选音质"), NEW("尚未下载"), MISSING("文件已丢失"), MODIFIED("文件已修改"), OTHER_QUALITY("已有其他音质");
        private final String label;
        Difference(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }
    public record FileRecord(Song song, AudioQuality quality, Path directory, long bytes, long modified,
                             ProcessingOptions options, AudioResult result) { }
    public record DifferenceItem(Song song, Difference difference, FileRecord file) { }
    public record Comparison(List<DifferenceItem> items, List<Song> removed) {
        public Comparison { items = List.copyOf(items); removed = List.copyOf(removed); }
    }
    private record Intent(Path path, long bytes, long modified, String format, TrackMetadata metadata) { }
    private record TaskRecord(String id, long accountId, Song song, AudioQuality quality, OutputPlanner.Context context,
                              ProcessingOptions options, String batchId, TrackMetadata metadata, State state, String status,
                              AudioQuality actualQuality, MusicException.Kind failureKind, AudioResult result,
                              int attempts, long position, boolean hidden, Intent intent, Path temporary) { }
    private record BatchRecord(String id, long accountId, Source source, Source original, Destination destination,
                               ProcessingOptions options, List<Song> order, Map<Long, TrackMetadata> metadata,
                               Map<Long, AudioResult> existing, int skipped, boolean reported, Path playlistFile, String playlistWarning) { }

    private final List<Task> tasks = new ArrayList<>();
    private final Map<String, Batch> batches = new LinkedHashMap<>();
    private final Map<String, FileRecord> files = new LinkedHashMap<>();
    private final Map<String, List<Song>> collections = new HashMap<>();
    private final ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(6, r -> new Thread(r, "music-download"));
    private DownloadStore store;
    private LoginSession session;
    private int concurrency = 3;
    private int running;
    private int pendingPlaylists;
    private int pendingSubmissions;
    private long nextPosition;
    private boolean paused;
    private boolean closed;
    private volatile String warning = "";
    private Runnable listener = () -> { };
    private Runnable successListener = () -> { };
    private Consumer<BatchResult> batchListener = result -> { };

    public DownloadManager() { this(ApplicationPaths.directory().resolve("config/tasks")); }
    public DownloadManager(Path directory) {
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setRemoveOnCancelPolicy(true);
        try { store = new DownloadStore(directory); restore(); }
        catch (IOException | RuntimeException exception) {
            warning = "下载记录无法读取，下载已暂停：" + exception.getMessage();
            if (store != null) store.close();
            store = null;
        }
    }

    public synchronized void setListener(Runnable value) { listener = value; }
    public synchronized void setSuccessListener(Runnable value) { successListener = value; }
    public synchronized void setBatchListener(Consumer<BatchResult> value) { batchListener = value; }
    public synchronized List<Task> tasks() { return tasks.stream().filter(task -> !task.hidden).toList(); }
    public synchronized List<Batch> batches() { return List.copyOf(batches.values()); }
    public String warning() { return warning; }
    public synchronized int concurrency() { return concurrency; }
    public synchronized boolean paused() { return paused; }
    public synchronized boolean hasPendingTasks() {
        return running > 0 || pendingPlaylists > 0 || pendingSubmissions > 0
                || tasks.stream().anyMatch(task -> task.state == State.WAITING || task.state == State.RETRY_WAIT);
    }

    public synchronized void setSession(LoginSession value) {
        if (closed) return;
        session = value;
        for (var task : tasks) {
            if (task.session != null && task.session != value && !task.finished()) {
                if (task.started) { task.suspend = true; stop(task); }
                else { task.state = State.PAUSED; task.status = "等待登录原账号后继续"; persist(task); }
            }
            if (!task.started) attach(task);
        }
        for (var batch : batches.values()) if (matches(batch.accountId)) {
            batch.session = session;
            batch.processing = new AudioProcessingService(session);
            finishBatch(batch);
        }
        changed(); dispatch();
    }

    private boolean matches(long accountId) { return session != null && session.account() != null && session.account().userId() == accountId; }
    private void attach(Task task) {
        task.session = matches(task.accountId) ? session : null;
        task.processing = task.session == null ? null : new AudioProcessingService(task.session);
    }

    public synchronized boolean add(LoginSession session, Song song, AudioQuality quality, OutputPlanner.Context context,
                                    ProcessingOptions options, TrackMetadata metadata, AudioProcessingService processing) {
        if (!available()) return false;
        var task = create(session, song, quality, context, options, null, metadata, processing);
        if (task == null) return false;
        persist(task).thenRun(() -> ready(List.of(task)));
        changed(); return true;
    }

    public synchronized boolean useExisting(LoginSession session, Song song, AudioQuality quality, OutputPlanner.Context context,
                                            ProcessingOptions options, TrackMetadata metadata, AudioResult result) {
        if (!available()) return false;
        var task = create(session, song, quality, context, options, null, metadata, new AudioProcessingService(session));
        if (task == null) return false;
        task.state = State.SKIPPED; task.status = "已使用已有文件"; task.progress = "100%";
        task.result = result; task.output = result.file(); task.actualQuality = quality;
        persist(task); changed(); return true;
    }

    public synchronized void addBatch(Batch batch, List<Song> songs, AudioQuality quality) {
        if (!available()) return;
        var added = new ArrayList<Task>();
        var ids = new HashSet<Long>();
        var positions = new HashMap<Long, Integer>();
        for (int i = 0; i < batch.order.size(); i++) positions.putIfAbsent(batch.order.get(i).id(), i + 1);
        for (var song : songs) {
            if (!ids.add(song.id())) continue;
            var context = new OutputPlanner.Context(batch.destination.baseDirectory(), collectionName(batch.original),
                    positions.getOrDefault(song.id(), 1), null, null);
            var task = create(batch.session, song, quality, context, batch.options, batch,
                    batch.metadata.getOrDefault(song.id(), TrackMetadata.fromSong(song)), batch.processing);
            if (task == null) batch.skipped++;
            else { batch.tasks.add(task); added.add(task); }
        }
        if (added.isEmpty() && batch.existing.isEmpty()) return;
        batches.put(batch.id, batch); batch.remaining = added.size();
        var updates = new ArrayList<DownloadStore.Change>();
        updates.add(batchChange(batch));
        added.forEach(task -> updates.add(taskChange(task)));
        var key = collectionKey(batch.original, batch.destination.baseDirectory(), batch.accountId);
        if (key != null) {
            collections.put(key, batch.original.songs());
            updates.add(DownloadStore.Change.put("collection/" + key, batch.original.songs()));
        }
        pendingSubmissions++;
        persist(updates).whenComplete((ignored, error) -> {
            synchronized (this) {
                pendingSubmissions--;
                if (error == null) { ready(added); finishBatch(batch); }
                changed();
            }
        });
        changed();
    }

    private Task create(LoginSession current, Song song, AudioQuality quality, OutputPlanner.Context context,
                        ProcessingOptions options, Batch batch, TrackMetadata metadata, AudioProcessingService processing) {
        var target = OutputPlanner.path(context, metadata, quality.toString(), "audio", options);
        long account = current.account().userId();
        if (tasks.stream().anyMatch(task -> !task.finished() && task.accountId == account && task.song.id() == song.id()
                && task.quality == quality && task.options.equals(options)
                && OutputPlanner.path(task.context, task.metadata, task.quality.toString(), "audio", task.options).equals(target))) return null;
        var task = new Task(UUID.randomUUID().toString(), account, song, quality, context, options, batch, metadata);
        task.session = current; task.processing = processing; task.position = nextPosition++;
        tasks.add(task); return task;
    }

    private boolean available() { return !closed && store != null && warning.isBlank(); }
    private synchronized void ready(List<Task> values) { values.forEach(task -> task.ready = true); dispatch(); }

    public synchronized void setConcurrency(int value) {
        concurrency = Math.max(1, Math.min(6, value)); persistSettings(); dispatch(); changed();
    }
    public synchronized void setPaused(boolean value) { paused = value; persistSettings(); dispatch(); changed(); }
    private void persistSettings() { persist(List.of(DownloadStore.Change.put("settings", Map.of("concurrency", concurrency, "paused", paused)))); }

    public synchronized void pause(Collection<Task> selected) {
        for (var task : selected) if (task.state == State.WAITING || task.state == State.RETRY_WAIT) {
            task.state = State.PAUSED; task.status = "已暂停"; persist(task);
        }
        changed();
    }
    public synchronized void resume(Collection<Task> selected) {
        boolean resumed = false;
        for (var task : selected) if (task.state == State.PAUSED && !task.started && matches(task.accountId)) {
            resumed = true;
            attach(task); task.cancellation = new DownloadCancellation(); task.suspend = false;
            task.state = State.WAITING; task.status = "等待下载"; task.ready = false;
            persist(task).thenRun(() -> ready(List.of(task)));
        }
        if (resumed && paused) { paused = false; persistSettings(); }
        for (var batch : batches.values()) if (matches(batch.accountId)) finishBatch(batch);
        dispatch();
        changed();
    }
    public synchronized void retry(Collection<Task> selected) {
        for (var task : selected) if ((task.state == State.FAILED || task.state == State.CANCELLED) && matches(task.accountId)) {
            attach(task); task.cancellation = new DownloadCancellation(); task.suspend = false; task.hidden = false;
            task.attempts = 0; task.failureKind = null; task.progress = ""; task.state = State.WAITING;
            task.status = "等待下载"; task.ready = false;
            if (task.batch != null) {
                task.batch.reported = false; task.batch.remaining++; task.batch.revision++; persist(List.of(batchChange(task.batch)));
            }
            persist(task).thenRun(() -> ready(List.of(task)));
        }
        changed();
    }
    public synchronized void prioritize(Collection<Task> selected, boolean first) {
        var waiting = tasks.stream().filter(task -> !task.started && !task.finished()).toList();
        var reordered = new ArrayList<Task>();
        if (first) reordered.addAll(waiting.stream().filter(selected::contains).toList());
        reordered.addAll(waiting.stream().filter(task -> !selected.contains(task)).toList());
        if (!first) reordered.addAll(waiting.stream().filter(selected::contains).toList());
        var updates = new ArrayList<DownloadStore.Change>();
        for (var task : reordered) { task.position = nextPosition++; updates.add(taskChange(task)); }
        tasks.sort(Comparator.comparingLong(task -> task.position));
        persist(updates); changed(); dispatch();
    }
    public synchronized void cancel(Collection<Task> selected) {
        for (var task : selected) {
            if (task.finished()) continue;
            task.suspend = false;
            if (task.started) { task.status = "正在取消"; stop(task); }
            else { task.state = State.CANCELLED; task.status = "已取消"; persist(task); finishBatch(task.batch); }
        }
        changed(); dispatch();
    }
    private static void stop(Task task) { task.cancellation.cancel(); if (task.worker != null) task.worker.interrupt(); }
    public synchronized void clearFinished() {
        var updates = new ArrayList<DownloadStore.Change>();
        for (var task : tasks) if (task.finished() && !task.hidden) { task.hidden = true; updates.add(taskChange(task)); }
        persist(updates); changed();
    }

    private synchronized void dispatch() {
        if (!available() || paused) return;
        for (var task : tasks.stream().sorted(Comparator.comparingLong(item -> item.position)).toList()) {
            if (running >= concurrency) break;
            if (!task.ready || task.started || task.session == null || task.cancellation.isCancelled()) continue;
            if (task.state != State.WAITING && task.state != State.RETRY_WAIT) continue;
            if (task.state == State.RETRY_WAIT && task.retryAt > System.currentTimeMillis()) continue;
            task.started = true; task.state = State.RUNNING; task.status = "正在准备下载"; task.attempts++; running++;
            persist(task).whenComplete((ignored, error) -> {
                synchronized (this) {
                    if (error != null || closed) { finish(task, State.PAUSED, "等待继续", null); return; }
                    executor.execute(() -> run(task));
                }
            });
        }
        changed();
    }

    private void run(Task task) {
        synchronized (this) { task.worker = Thread.currentThread(); }
        try {
            task.cancellation.check();
            if (task.temporary != null && task.temporary.getParent().equals(task.directory)
                    && task.temporary.getFileName().toString().startsWith(".ncm-breaker-download-")
                    && task.temporary.getFileName().toString().endsWith(".part")) Files.deleteIfExists(task.temporary);
            task.temporary = null;
            var recovered = recoverOutput(task);
            if (recovered != null) { finish(task, State.SUCCEEDED, "已恢复保存的音频，请查看附加文件提示", recovered); return; }
            var service = new MusicService(task.session);
            var song = task.song;
            if (!song.detailAvailable() || song.coverUrl().isBlank()) {
                update(task, "正在获取歌曲信息", "");
                var details = service.songs(List.of(song.id()), count -> { });
                if (!details.isEmpty() && details.get(0).detailAvailable()) song = details.get(0);
                if (!song.detailAvailable()) throw new MusicException("歌曲详情暂不可用，请稍后重试");
                task.song = song;
            }
            task.cancellation.check(); update(task, "正在获取下载地址", "");
            var authorization = service.authorize(song.id(), task.quality);
            task.actualQuality = authorization.actual();
            if (authorization.actual() != task.quality) throw new MusicException(MusicException.Kind.QUALITY, "当前返回" + authorization.actual() + "，请重新选择音质");
            long start = System.nanoTime();
            var result = new AudioDownloader().download(authorization, task.metadata.fillMissing(TrackMetadata.fromSong(song)),
                    task.context, task.options, task.processing, task.cancellation,
                    (done, total) -> update(task, done == total ? "正在校验" : "正在下载",
                            "%.1f%%  %.1f / %.1f MB  %.1f MB/s".formatted(done * 100.0 / total, done / 1048576.0,
                                    total / 1048576.0, done / 1048576.0 / Math.max(0.1, (System.nanoTime() - start) / 1_000_000_000.0))),
                    stage -> update(task, stage, "100%"), new AudioDownloader.CommitListener() {
                        @Override public void beforeMove(Path temporary, Path target, TrackMetadata metadata) throws IOException {
                            rememberIntent(task, temporary, target, metadata);
                        }
                        @Override public void temporaryCreated(Path temporary) throws IOException {
                            CompletableFuture<Void> saved;
                            synchronized (DownloadManager.this) { task.temporary = temporary; saved = persist(task); }
                            try { saved.join(); }
                            catch (CompletionException exception) { throw new IOException("无法保存下载任务", exception); }
                        }
                    });
            boolean skipped = result.outcome() == AudioResult.Outcome.SKIPPED;
            String message = skipped ? "已跳过：目标文件已存在" : "完成：" + result.format().toUpperCase(Locale.ROOT);
            if (!result.warningText().isBlank()) message += "，" + result.warningText();
            finish(task, skipped ? State.SKIPPED : State.SUCCEEDED, message, result);
        } catch (Exception exception) {
            boolean cancelled = task.cancellation.isCancelled() || exception instanceof CancellationException || exception instanceof InterruptedException;
            synchronized (this) {
                if (cancelled) finish(task, closed || task.suspend ? State.PAUSED : State.CANCELLED,
                        closed || task.suspend ? "等待继续" : "已取消", null);
                else {
                    task.failureKind = MusicException.kind(exception);
                    if (task.failureKind == MusicException.Kind.NETWORK && task.attempts < 3 && available()) {
                        task.retryAt = System.currentTimeMillis() + (task.attempts == 1 ? 3000 : 9000);
                        finish(task, State.RETRY_WAIT, "网络请求失败，等待第 " + task.attempts + " 次重试", null);
                        executor.schedule(this::dispatch, task.attempts == 1 ? 3 : 9, TimeUnit.SECONDS);
                    } else finish(task, State.FAILED, MusicException.describe(exception), null);
                }
            }
        } finally { synchronized (this) { if (task.worker == Thread.currentThread()) task.worker = null; } }
    }

    private void rememberIntent(Task task, Path temporary, Path target, TrackMetadata metadata) throws IOException {
        CompletableFuture<Void> saved;
        synchronized (this) {
            var name = target.getFileName().toString();
            task.intent = new Intent(target, Files.size(temporary), Files.getLastModifiedTime(temporary).toMillis(),
                    name.substring(name.lastIndexOf('.') + 1), metadata);
            saved = persist(task);
        }
        try { saved.join(); }
        catch (CompletionException exception) { throw new IOException("无法保存任务状态，音频尚未移动到目标目录", exception); }
    }

    private static AudioResult recoverOutput(Task task) {
        var intent = task.intent;
        if (intent == null) return null;
        try {
            if (!Files.isRegularFile(intent.path()) || Files.size(intent.path()) != intent.bytes()
                    || Files.getLastModifiedTime(intent.path()).toMillis() != intent.modified()) return null;
            return new AudioResult(intent.path(), intent.format(), intent.bytes(), intent.metadata(), AudioResult.Outcome.WRITTEN, List.of(),
                    task.options.saveLyrics() || task.options.exportCover()
                            ? List.of(new AudioResult.Warning(AudioResult.WarningCode.ADDITIONAL_FILE, "音频已恢复；上次处理曾中断，请检查外置歌词和封面")) : List.of());
        } catch (IOException exception) { return null; }
    }

    private synchronized void update(Task task, String status, String progress) {
        if (closed || task.cancellation.isCancelled() || !task.started) return;
        task.status = status; task.progress = progress; changed();
    }

    private synchronized void finish(Task task, State state, String status, AudioResult result) {
        if (task.started) { running--; task.started = false; task.worker = null; }
        task.state = state; task.status = status;
        if (task.temporary != null && Files.notExists(task.temporary)) task.temporary = null;
        if (result != null) { task.result = result; task.output = result.file(); task.intent = null; }
        task.progress = state == State.SUCCEEDED || state == State.SKIPPED ? "100%" : "";
        var updates = new ArrayList<DownloadStore.Change>();
        updates.add(taskChange(task));
        if (result != null && state == State.SUCCEEDED) {
            try {
                var record = new FileRecord(task.song, task.actualQuality == null ? task.quality : task.actualQuality,
                        task.directory, Files.size(result.file()), Files.getLastModifiedTime(result.file()).toMillis(), task.options, result);
                files.put(result.file().toString(), record);
                updates.add(DownloadStore.Change.put("file/" + result.file(), record));
            } catch (IOException exception) { task.status += "；无法记录输出文件信息"; updates.set(0, taskChange(task)); }
        }
        persist(updates);
        finishBatch(task.batch); changed();
        if (!closed && state == State.SUCCEEDED) successListener.run();
        if (!closed) dispatch();
    }

    private synchronized void finishBatch(Batch batch) {
        if (batch == null) return;
        batch.remaining = (int) batch.tasks.stream().filter(task -> !task.finished()).count();
        if (closed || batch.remaining > 0 || batch.reported || batch.finalizing) return;
        batch.finalizing = true; pendingPlaylists++;
        int revision = batch.revision;
        var results = batchResult(batch);
        var outputs = new HashMap<>(batch.existing);
        for (var task : batch.tasks) if (task.result != null) outputs.put(task.song.id(), task.result);
        executor.execute(() -> {
            Path playlist = batch.playlistFile;
            String message = "";
            if (batch.options.playlist()) {
                try {
                    var ordered = batch.order.stream().map(song -> outputs.get(song.id())).filter(Objects::nonNull).toList();
                    var name = playlist == null ? collectionName(batch.original)
                            : playlist.getFileName().toString().replaceFirst("(?i)\\.m3u8$", "");
                    playlist = OutputPlanner.writePlaylist(batch.destination.baseDirectory(), name, ordered,
                            playlist == null ? batch.options.conflictPolicy() : com.ncmbreaker.NcmFileIO.ConflictPolicy.OVERWRITE);
                } catch (Exception exception) { message = "M3U8 未保存：" + exception.getMessage(); }
            }
            synchronized (this) {
                batch.finalizing = false; pendingPlaylists--; batch.reported = revision == batch.revision;
                batch.playlistFile = playlist; batch.playlistWarning = message; persist(List.of(batchChange(batch))); changed();
                if (!closed && batch.reported) batchListener.accept(new BatchResult(batch.id, batch.session, batch.source, batch.original,
                        batch.destination, results.tasks(), results.skipped(), results.reused(), playlist, message));
                else finishBatch(batch);
            }
        });
    }

    public synchronized BatchResult batchResult(Batch batch) {
        var results = batch.tasks.stream().map(task -> new TaskResult(task.id, task.song, task.state, task.status,
                task.quality, task.actualQuality, task.options, task.failureKind, task.result)).toList();
        return new BatchResult(batch.id, batch.session, batch.source, batch.original, batch.destination, results,
                batch.skipped, batch.existing.size(), batch.playlistFile, batch.playlistWarning);
    }

    public Comparison compare(List<Song> songs, AudioQuality quality, Path directory, Source source, long accountId) {
        final List<FileRecord> saved;
        final List<Song> previous;
        synchronized (this) {
            saved = List.copyOf(files.values());
            previous = collections.getOrDefault(collectionKey(source, directory, accountId), List.of());
        }
        var root = directory.toAbsolutePath().normalize();
        var bySong = new HashMap<Long, List<FileRecord>>();
        for (var record : saved) if (record.directory().equals(root)) bySong.computeIfAbsent(record.song().id(), ignored -> new ArrayList<>()).add(record);
        var result = new ArrayList<DifferenceItem>();
        for (var song : songs) {
            var records = bySong.getOrDefault(song.id(), List.of());
            FileRecord match = null; Difference difference = Difference.NEW;
            for (var record : records) {
                var health = health(record);
                if (record.quality() == quality) {
                    if (health == Difference.PRESENT) { match = record; difference = health; break; }
                    match = record; difference = health;
                } else if (match == null && health == Difference.PRESENT) { match = record; difference = Difference.OTHER_QUALITY; }
            }
            result.add(new DifferenceItem(song, difference, match));
        }
        var ids = songs.stream().map(Song::id).collect(java.util.stream.Collectors.toSet());
        var removed = previous.stream().filter(song -> source != null && source.songs().stream().noneMatch(current -> current.id() == song.id()))
                .filter(song -> !ids.contains(song.id())).toList();
        return new Comparison(result, removed);
    }

    public static Difference health(FileRecord record) {
        try {
            if (!Files.isRegularFile(record.result().file())) return Difference.MISSING;
            return Files.size(record.result().file()) == record.bytes()
                    && Files.getLastModifiedTime(record.result().file()).toMillis() == record.modified() ? Difference.PRESENT : Difference.MODIFIED;
        } catch (IOException | SecurityException exception) { return Difference.MODIFIED; }
    }

    private static String collectionName(Source source) {
        return source.playlist() == null ? source.name() : PlaylistWorkbook.baseName(source.playlist());
    }
    private static String collectionKey(Source source, Path directory, long accountId) {
        if (source == null || source.playlist() == null || source.playlist().playlist().id() <= 0) return null;
        return accountId + ":" + source.playlist().type().name() + ":" + source.playlist().playlist().id() + ":" + directory.toAbsolutePath().normalize();
    }
    private DownloadStore.Change taskChange(Task task) {
        return DownloadStore.Change.put("task/" + task.id, new TaskRecord(task.id, task.accountId, task.song, task.quality,
                task.context, task.options, task.batch == null ? null : task.batch.id, task.metadata, task.state, task.status,
                task.actualQuality, task.failureKind, task.result, task.attempts, task.position, task.hidden, task.intent, task.temporary));
    }
    private DownloadStore.Change batchChange(Batch batch) {
        return DownloadStore.Change.put("batch/" + batch.id, new BatchRecord(batch.id, batch.accountId, batch.source, batch.original,
                batch.destination, batch.options, batch.order, batch.metadata, Map.copyOf(batch.existing), batch.skipped,
                batch.reported, batch.playlistFile, batch.playlistWarning));
    }
    private CompletableFuture<Void> persist(Task task) { return persist(List.of(taskChange(task))); }
    private CompletableFuture<Void> persist(List<DownloadStore.Change> changes) {
        if (store == null) return CompletableFuture.failedFuture(new IOException(warning));
        if (changes.isEmpty()) return CompletableFuture.completedFuture(null);
        var future = store.append(changes);
        future.whenComplete((ignored, error) -> {
            if (error != null) { warning = "下载记录无法保存，已停止启动新任务：" + MusicException.describe(error); listener.run(); }
        });
        return future;
    }
    private void changed() { listener.run(); }

    private void restore() {
        var records = store.loaded();
        for (var entry : records.entrySet()) {
            if (entry.getKey().startsWith("batch/")) {
                var record = DownloadStore.JSON.fromJson(entry.getValue(), BatchRecord.class);
                var batch = new Batch(record.id(), record.accountId(), record.source(), record.original(), record.destination(),
                        record.options(), record.order(), record.metadata());
                batch.existing.putAll(record.existing()); batch.skipped = record.skipped(); batch.reported = record.reported();
                batch.playlistFile = record.playlistFile(); batch.playlistWarning = record.playlistWarning(); batches.put(batch.id, batch);
            } else if (entry.getKey().startsWith("file/")) {
                var record = DownloadStore.JSON.fromJson(entry.getValue(), FileRecord.class);
                files.put(record.result().file().toString(), record);
            } else if (entry.getKey().startsWith("collection/")) {
                collections.put(entry.getKey().substring(11), List.of(DownloadStore.JSON.fromJson(entry.getValue(), Song[].class)));
            } else if (entry.getKey().equals("settings")) {
                var value = entry.getValue().getAsJsonObject(); concurrency = Math.max(1, Math.min(6, value.get("concurrency").getAsInt()));
                paused = value.get("paused").getAsBoolean();
            }
        }
        for (var entry : records.entrySet()) if (entry.getKey().startsWith("task/")) {
            var record = DownloadStore.JSON.fromJson(entry.getValue(), TaskRecord.class);
            var batch = batches.get(record.batchId());
            var task = new Task(record.id(), record.accountId(), record.song(), record.quality(), record.context(), record.options(), batch, record.metadata());
            task.state = record.state().finished() ? record.state() : State.PAUSED;
            task.status = record.state().finished() ? record.status() : "上次未完成，等待继续";
            task.actualQuality = record.actualQuality(); task.failureKind = record.failureKind(); task.result = record.result();
            task.output = task.result == null ? null : task.result.file(); task.attempts = record.attempts(); task.position = record.position();
            task.hidden = record.hidden(); task.intent = record.intent(); task.temporary = record.temporary(); task.ready = true;
            if (task.state == State.SUCCEEDED || task.state == State.SKIPPED) task.progress = "100%";
            tasks.add(task); if (batch != null) batch.tasks.add(task); nextPosition = Math.max(nextPosition, task.position + 1);
        }
        tasks.sort(Comparator.comparingLong(task -> task.position));
        for (var batch : batches.values()) batch.remaining = (int) batch.tasks.stream().filter(task -> !task.finished()).count();
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        for (var task : tasks) if (!task.finished()) {
            task.suspend = true;
            if (task.started) stop(task);
            task.state = State.PAUSED; task.status = "等待继续"; persist(task);
        }
        executor.shutdown();
        new Thread(() -> {
            try {
                while (!executor.awaitTermination(1, TimeUnit.SECONDS)) { }
            } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            finally { if (store != null) store.close(); }
        }, "download-shutdown").start();
    }
}
