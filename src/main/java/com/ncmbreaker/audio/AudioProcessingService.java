package com.ncmbreaker.audio;

import com.ncmbreaker.NcmFileIO;
import com.ncmbreaker.download.DownloadCancellation;
import com.ncmbreaker.netease.auth.LoginSession;
import com.ncmbreaker.netease.music.MusicService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import static com.ncmbreaker.audio.AudioResult.WarningCode.*;

public final class AudioProcessingService {
    public record Prepared(TrackMetadata metadata, Lyrics lyrics, List<AudioResult.Warning> warnings) {
        public Prepared { warnings = List.copyOf(warnings); }
    }
    private final LoginSession session;
    private final ConcurrentHashMap<Long, FutureTask<TrackMetadata>> songs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, FutureTask<Map<Long, TrackMetadata>>> albums = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, FutureTask<Lyrics>> lyrics = new ConcurrentHashMap<>();

    public AudioProcessingService(LoginSession session) { this.session = session; }

    public void prefetchMetadata(List<TrackMetadata> inputs, DownloadCancellation cancellation) throws Exception {
        if (session == null || session.account() == null) return;
        var ids = inputs.stream().map(TrackMetadata::songId).filter(id -> id > 0 && !songs.containsKey(id)).distinct().toList();
        if (ids.isEmpty()) return;
        cancellation.check();
        var found = new MusicService(session).songMetadata(ids);
        cancellation.check();
        found.forEach((id, metadata) -> {
            var task = new FutureTask<>(() -> metadata); task.run(); songs.putIfAbsent(id, task);
        });
    }

    public Prepared prepare(TrackMetadata metadata, ProcessingOptions options, DownloadCancellation cancellation) {
        var warnings = new ArrayList<AudioResult.Warning>();
        var service = session == null || session.account() == null ? null : new MusicService(session);
        if (options.enrich()) {
            try {
                cancellation.check();
                if (service == null) throw new IllegalStateException("请登录后补全歌曲资料");
                if (metadata.songId() <= 0 && metadata.albumId() <= 0) throw new IllegalStateException("没有可用于补全资料的歌曲或专辑 ID");
                long songId = metadata.songId();
                if (songId > 0) metadata = metadata.fillMissing(cached(songs, songId, () -> service.songMetadata(songId)));
                if (metadata.albumId() > 0) {
                    long albumId = metadata.albumId();
                    var album = cached(albums, albumId, () -> service.albumMetadata(albumId));
                    metadata = metadata.fillMissing(album.getOrDefault(songId, album.get(0L)));
                }
            } catch (Exception exception) { rethrowCancellation(exception, cancellation); warnings.add(new AudioResult.Warning(METADATA, "歌曲资料未补全：" + exception.getMessage())); }
        }
        var result = Lyrics.unavailable();
        if (options.needsLyrics()) {
            try {
                cancellation.check();
                if (service == null) throw new IllegalStateException("请登录后获取歌词");
                if (metadata.songId() <= 0) throw new IllegalStateException("缺少歌曲 ID，无法获取歌词");
                long id = metadata.songId();
                result = cached(lyrics, id, () -> service.lyrics(id));
                if (result.state() != Lyrics.State.AVAILABLE) warnings.add(new AudioResult.Warning(LYRICS,
                        result.state() == Lyrics.State.INSTRUMENTAL ? "纯音乐，无歌词" : "此歌曲未提供歌词"));
                else {
                    if (options.translation() && result.translated().isBlank()) warnings.add(new AudioResult.Warning(LYRICS, "此歌曲未提供译文"));
                    if (options.romanization() && result.romanized().isBlank()) warnings.add(new AudioResult.Warning(LYRICS, "此歌曲未提供音译"));
                }
            } catch (Exception exception) { rethrowCancellation(exception, cancellation); warnings.add(new AudioResult.Warning(LYRICS, "歌词获取失败：" + exception.getMessage())); }
        }
        cancellation.check();
        return new Prepared(metadata, result, warnings);
    }

    private static void rethrowCancellation(Exception exception, DownloadCancellation cancellation) {
        if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
        if (exception instanceof java.util.concurrent.CancellationException cancelled) throw cancelled;
        cancellation.check();
    }

    private static <T> T cached(ConcurrentHashMap<Long, FutureTask<T>> cache, long id, Callable<T> request) throws Exception {
        var task = cache.computeIfAbsent(id, ignored -> new FutureTask<>(request));
        task.run();
        try { return task.get(); }
        catch (ExecutionException exception) {
            cache.remove(id, task);
            if (exception.getCause() instanceof Exception cause) throw cause;
            throw exception;
        }
    }

    public AudioResult save(Path temporary, String format, long audioBytes, Prepared prepared, byte[] containerCover,
                            OutputPlanner.Context context, String quality, ProcessingOptions options,
                            DownloadCancellation cancellation) throws Exception {
        return save(temporary, format, audioBytes, prepared, containerCover, context, quality, options, cancellation,
                (source, target) -> { });
    }

    public AudioResult save(Path temporary, String format, long audioBytes, Prepared prepared, byte[] containerCover,
                            OutputPlanner.Context context, String quality, ProcessingOptions options,
                            DownloadCancellation cancellation, OutputPlanner.CommitListener listener) throws Exception {
        var warnings = new ArrayList<>(prepared.warnings());
        var metadata = prepared.metadata();
        var desired = OutputPlanner.path(context, metadata, quality, format, options);
        if (options.conflictPolicy() == NcmFileIO.ConflictPolicy.SKIP && Files.isRegularFile(desired)) {
            return new AudioResult(desired, format, 0, metadata, AudioResult.Outcome.SKIPPED, List.of(), warnings);
        }
        var text = prepared.lyrics().content(options.translation(), options.romanization());
        var image = AudioTagWriter.write(temporary, format, metadata, options, text, containerCover,
                () -> context.source() != null && !options.enrich() ? new byte[0]
                        : CoverArt.load(metadata.coverUrl(), cancellation), cancellation, warnings);
        var imageExtension = CoverArt.extension(image);
        var companions = new ArrayList<String>();
        if (options.saveLyrics() && !text.isBlank()) companions.add(".lrc");
        if (options.exportCover() && !options.albumCover() && !imageExtension.isEmpty()) companions.add(imageExtension);
        cancellation.check();
        var saved = OutputPlanner.commit(temporary, desired, options.conflictPolicy(), companions, cancellation, listener);
        if (saved.skipped()) return new AudioResult(saved.path(), format, 0, metadata, AudioResult.Outcome.SKIPPED, List.of(), warnings);
        var files = new ArrayList<Path>();
        if (cancellation.isCancelled() || Thread.currentThread().isInterrupted()) {
            warnings.add(new AudioResult.Warning(ADDITIONAL_FILE, "音频已保存，附加文件处理已取消"));
        } else {
            if (options.saveLyrics() && !text.isBlank()) writeAdditional(OutputPlanner.companion(saved.path(), ".lrc"),
                    text.getBytes(StandardCharsets.UTF_8), options, files, warnings);
            if (options.exportCover() && !imageExtension.isEmpty()) {
                var coverPath = options.albumCover()
                        ? saved.path().getParent().resolve(OutputPlanner.safeName(metadata.album().isBlank() ? "cover" : metadata.album())
                            + (metadata.albumId() > 0 ? "_" + metadata.albumId() : "") + imageExtension)
                        : OutputPlanner.companion(saved.path(), imageExtension);
                writeAdditional(coverPath, image, options, files, warnings);
            }
        }
        return new AudioResult(saved.path(), format, audioBytes, metadata, AudioResult.Outcome.WRITTEN, files, warnings);
    }

    private static void writeAdditional(Path target, byte[] data, ProcessingOptions options, List<Path> files,
                                         List<AudioResult.Warning> warnings) {
        try { files.add(OutputPlanner.writeAdditional(target, data, options.conflictPolicy())); }
        catch (Exception exception) { warnings.add(new AudioResult.Warning(ADDITIONAL_FILE,
                target.getFileName() + " 未保存：" + exception.getMessage())); }
    }

    public AudioResult convert(Path source, OutputPlanner.Context context, ProcessingOptions options,
                               DownloadCancellation cancellation, NcmFileIO.ProgressListener progress,
                               TrackMetadata selectedMetadata) throws Exception {
        var io = new NcmFileIO();
        var inspection = io.inspect(source);
        if (!inspection.convertible()) throw new java.io.IOException(inspection.detail());
        var prepared = prepare(selectedMetadata == null ? TrackMetadata.fromNcm(inspection.metadata()) : selectedMetadata, options, cancellation);
        var desired = OutputPlanner.path(context, prepared.metadata(), "", inspection.audioFormat(), options);
        if (options.conflictPolicy() == NcmFileIO.ConflictPolicy.SKIP && Files.isRegularFile(desired)) {
            return new AudioResult(desired, inspection.audioFormat(), 0, prepared.metadata(), AudioResult.Outcome.SKIPPED, List.of(), prepared.warnings());
        }
        OutputPlanner.preflight(context.directory(), inspection.audioSize(), options.needsTags() || options.exportCover());
        try (var decoded = io.decodeAudio(source, context.directory(), options.needsCover(), (done, total) -> {
            cancellation.check(); progress.onProgress(done, total);
        })) {
            return save(decoded.file(), decoded.format(), decoded.audioBytes(), prepared, decoded.cover(), context, "", options, cancellation);
        }
    }

    public AudioResult exportCover(Path source, OutputPlanner.Context context, ProcessingOptions options,
                                   DownloadCancellation cancellation) throws Exception {
        var io = new NcmFileIO();
        var inspection = io.inspect(source);
        if (inspection.metadata() == null) throw new java.io.IOException(inspection.detail());
        var metadataOnly = new ProcessingOptions(false, false, options.enrich(), false, false, false, false, false,
                true, options.albumCover(), false, options.layout(), options.template(), options.conflictPolicy());
        var prepared = prepare(TrackMetadata.fromNcm(inspection.metadata()), metadataOnly, cancellation);
        var image = io.coverData(source);
        if (image.length == 0 && options.enrich()) image = CoverArt.load(prepared.metadata().coverUrl(), cancellation);
        var extension = CoverArt.extension(image);
        if (extension.isEmpty()) throw new java.io.IOException("此文件没有可导出的有效封面");
        var desired = OutputPlanner.path(context, prepared.metadata(), "", extension.substring(1), options);
        if (options.albumCover()) desired = desired.getParent().resolve(OutputPlanner.safeName(prepared.metadata().album().isBlank()
                ? "cover" : prepared.metadata().album()) + (prepared.metadata().albumId() > 0 ? "_" + prepared.metadata().albumId() : "") + extension);
        OutputPlanner.preflight(context.directory(), image.length, false);
        var temporary = Files.createTempFile(context.directory(), ".ncm-breaker-cover-", ".tmp");
        try {
            Files.write(temporary, image);
            var saved = OutputPlanner.commit(temporary, desired, options.conflictPolicy(), List.of(), cancellation);
            return new AudioResult(saved.path(), extension.substring(1), 0, prepared.metadata(),
                    saved.skipped() ? AudioResult.Outcome.SKIPPED : AudioResult.Outcome.WRITTEN, List.of(), prepared.warnings());
        } finally { Files.deleteIfExists(temporary); }
    }
}
