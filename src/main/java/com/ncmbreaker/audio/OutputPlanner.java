package com.ncmbreaker.audio;

import com.ncmbreaker.NcmFileIO.ConflictPolicy;
import com.ncmbreaker.download.DownloadCancellation;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class OutputPlanner {
    private static final Object OUTPUT_LOCK = new Object();
    private static final Pattern FIELD = Pattern.compile("\\{([A-Za-z]+)}");
    private static final Set<String> FIELDS = Set.of("artist", "albumArtist", "album", "title", "track", "disc",
            "index", "playlist", "id", "quality", "format");
    public record Context(Path directory, String collection, int index, Path source, Path inputRoot) {
        public Context {
            directory = directory.toAbsolutePath().normalize();
            collection = collection == null ? "" : collection;
        }
    }
    public record Saved(Path path, boolean skipped) { }
    @FunctionalInterface public interface CommitListener {
        void beforeMove(Path temporary, Path target) throws IOException;
    }
    private OutputPlanner() { }

    public static void validate(ProcessingOptions options) {
        if (options.layout() != ProcessingOptions.Layout.CUSTOM) return;
        var template = options.template();
        if (template.isBlank()) throw new IllegalArgumentException("命名模板不能为空");
        if (template.startsWith("/") || template.startsWith("\\") || template.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("命名模板必须使用相对目录");
        }
        var matcher = FIELD.matcher(template);
        while (matcher.find()) if (!FIELDS.contains(matcher.group(1))) {
            throw new IllegalArgumentException("未知命名字段：" + matcher.group());
        }
        if (FIELD.matcher(template).replaceAll("").matches("(?s).*[{}].*")) {
            throw new IllegalArgumentException("命名字段的括号不完整");
        }
        for (var part : template.split("[\\\\/]", -1)) {
            if (part.equals(".") || part.equals("..")) throw new IllegalArgumentException("命名模板不能包含相对目录跳转");
        }
    }

    public static Path path(Context context, TrackMetadata metadata, String quality, String format,
                            ProcessingOptions options) {
        validate(options);
        var title = metadata.title().isBlank() ? metadata.songId() > 0 ? Long.toString(metadata.songId()) : "audio" : metadata.title();
        Path relative;
        if (options.layout() == ProcessingOptions.Layout.CURRENT || options.layout() == ProcessingOptions.Layout.SOURCE) {
            String name;
            if (context.source() != null) {
                name = context.source().getFileName().toString().replaceFirst("(?i)\\.ncm$", "");
            } else {
                name = (metadata.artistText().isBlank() ? "" : metadata.artistText() + " - ") + title;
                if (quality != null && !quality.isBlank()) name += " [" + quality + "]";
            }
            relative = Path.of(safeName(name));
            if (options.layout() == ProcessingOptions.Layout.SOURCE && context.source() != null && context.inputRoot() != null) {
                var source = context.source().toAbsolutePath().normalize();
                var root = context.inputRoot().toAbsolutePath().normalize();
                if (!source.startsWith(root)) throw new IllegalArgumentException("输入文件不在所选目录内");
                var parent = root.relativize(source).getParent();
                if (parent != null) relative = parent.resolve(relative);
            }
        } else {
            var values = Map.ofEntries(Map.entry("artist", metadata.artistText().isBlank() ? "未知歌手" : metadata.artistText()),
                    Map.entry("albumArtist", metadata.albumArtistText().isBlank() ? metadata.artistText().isBlank() ? "未知歌手" : metadata.artistText() : metadata.albumArtistText()),
                    Map.entry("album", metadata.album().isBlank() ? "未知专辑" : metadata.album()), Map.entry("title", title),
                    Map.entry("track", padded(metadata.track())), Map.entry("disc", padded(metadata.disc())),
                    Map.entry("index", padded(context.index())), Map.entry("playlist", context.collection().isBlank() ? "本地转换" : context.collection()),
                    Map.entry("id", metadata.songId() > 0 ? Long.toString(metadata.songId()) : ""),
                    Map.entry("quality", quality == null ? "" : quality), Map.entry("format", format));
            var template = switch (options.layout()) {
                case ARTIST_ALBUM -> "{albumArtist}/{album}/{track} - {title}";
                case PLAYLIST -> "{playlist}/{index} - {artist} - {title}";
                default -> options.template();
            };
            relative = Path.of("");
            for (var part : template.split("[\\\\/]")) {
                if (part.isBlank()) continue;
                var matcher = FIELD.matcher(part);
                var rendered = matcher.replaceAll(match -> java.util.regex.Matcher.quoteReplacement(
                        values.get(match.group(1)).isBlank() ? "" : safeName(values.get(match.group(1)))));
                rendered = rendered.replaceAll("^[\\s._-]+|[\\s._-]+$", "");
                if (!rendered.isBlank()) relative = relative.resolve(safeName(rendered));
            }
            if (relative.toString().isBlank()) relative = Path.of(safeName(title));
        }
        var path = context.directory().resolve(relative + "." + format).normalize();
        if (!path.startsWith(context.directory())) throw new IllegalArgumentException("输出路径超出保存目录");
        return path;
    }

    private static String padded(int value) { return value > 0 ? "%02d".formatted(value) : ""; }
    public static String safeName(String value) {
        var name = value.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").strip().replaceAll("[. ]+$", "");
        if (name.isBlank()) name = "audio";
        if (name.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?")) name = "_" + name;
        var limited = name.substring(0, name.offsetByCodePoints(0, Math.min(120, name.codePointCount(0, name.length())))).replaceAll("[. ]+$", "");
        return limited.isBlank() ? "audio" : limited;
    }

    public static Path previewPath(Path desired, ConflictPolicy policy, Set<Path> planned, List<String> companions) {
        if (policy != ConflictPolicy.RENAME) { planned.add(desired); return desired; }
        for (int index = 0; ; index++) {
            var candidate = numbered(desired, index);
            if (Files.exists(candidate) || planned.contains(candidate)
                    || companions.stream().anyMatch(extension -> Files.exists(companion(candidate, extension))
                            || planned.contains(companion(candidate, extension)))) continue;
            planned.add(candidate);
            companions.forEach(extension -> planned.add(companion(candidate, extension)));
            return candidate;
        }
    }

    public static void preflight(Path directory, long audioBytes, boolean rewrite) throws IOException {
        Files.createDirectories(directory);
        var probe = Files.createTempFile(directory, ".ncm-breaker-probe-", ".tmp");
        Files.delete(probe);
        checkSpace(directory, audioBytes, rewrite);
    }

    public static void checkSpace(Path directory, long audioBytes, boolean rewrite) throws IOException {
        if (audioBytes <= 0) return;
        long required;
        try { required = Math.addExact(Math.multiplyExact(audioBytes, rewrite ? 2 : 1), 16L * 1024 * 1024); }
        catch (ArithmeticException exception) { throw new IOException("文件过大，无法估算保存空间", exception); }
        if (Files.getFileStore(directory).getUsableSpace() < required) throw new IOException("保存目录空间不足，预计需要至少 %.1f MB".formatted(required / 1048576.0));
    }

    public static void preflightBatch(Path directory, long audioBytes, long temporaryBytes) throws IOException {
        preflight(directory, 0, false);
        long required;
        try { required = Math.addExact(Math.addExact(audioBytes, temporaryBytes), 16L * 1024 * 1024); }
        catch (ArithmeticException exception) { throw new IOException("文件过大，无法估算保存空间", exception); }
        if (Files.getFileStore(directory).getUsableSpace() < required) throw new IOException("保存目录空间不足，预计需要至少 %.1f MB".formatted(required / 1048576.0));
    }

    public static Saved commit(Path temporary, Path desired, ConflictPolicy policy, List<String> companions,
                               DownloadCancellation cancellation) throws IOException {
        return commit(temporary, desired, policy, companions, cancellation, (source, target) -> { });
    }

    public static Saved commit(Path temporary, Path desired, ConflictPolicy policy, List<String> companions,
                               DownloadCancellation cancellation, CommitListener listener) throws IOException {
        Files.createDirectories(desired.getParent());
        synchronized (OUTPUT_LOCK) {
            for (int suffix = 0; ; suffix++) {
                cancellation.check();
                var target = numbered(desired, suffix);
                if (Files.exists(target) && policy == ConflictPolicy.SKIP) {
                    if (!Files.isRegularFile(target)) throw new IOException("目标路径不是普通文件：" + target);
                    return new Saved(target, true);
                }
                if (policy == ConflictPolicy.RENAME && (Files.exists(target)
                        || companions.stream().anyMatch(extension -> Files.exists(companion(target, extension))))) continue;
                try {
                    listener.beforeMove(temporary, target);
                    cancellation.check();
                    if (policy == ConflictPolicy.OVERWRITE) Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                    else Files.move(temporary, target);
                    return new Saved(target, false);
                } catch (FileAlreadyExistsException exception) {
                    if (policy == ConflictPolicy.OVERWRITE) throw exception;
                    if (policy == ConflictPolicy.SKIP) {
                        if (!Files.isRegularFile(target)) throw exception;
                        return new Saved(target, true);
                    }
                }
            }
        }
    }

    public static Path companion(Path audio, String extension) {
        var name = audio.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return audio.resolveSibling((dot < 0 ? name : name.substring(0, dot)) + extension);
    }

    private static Path numbered(Path desired, int suffix) {
        if (suffix == 0) return desired;
        var name = desired.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return desired.resolveSibling((dot < 0 ? name : name.substring(0, dot)) + " (" + suffix + ")" + (dot < 0 ? "" : name.substring(dot)));
    }

    public static Path writeAdditional(Path target, byte[] bytes, ConflictPolicy policy) throws IOException {
        Files.createDirectories(target.getParent());
        var temporary = Files.createTempFile(target.getParent(), ".ncm-breaker-output-", ".tmp");
        try {
            Files.write(temporary, bytes);
            synchronized (OUTPUT_LOCK) {
                if (Files.exists(target) && policy != ConflictPolicy.OVERWRITE) {
                    if (Files.isRegularFile(target) && Files.size(target) == bytes.length
                            && java.util.Arrays.equals(Files.readAllBytes(target), bytes)) return target;
                    throw new FileAlreadyExistsException(target.toString(), null, "已有同名文件，未覆盖");
                }
                if (policy == ConflictPolicy.OVERWRITE) Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                else Files.move(temporary, target);
                return target;
            }
        } finally { Files.deleteIfExists(temporary); }
    }

    public static Path writePlaylist(Path directory, String name, List<AudioResult> entries, ConflictPolicy policy)
            throws IOException {
        var usable = entries.stream().filter(result -> result != null && Files.isRegularFile(result.file())).toList();
        if (usable.isEmpty()) return null;
        var desired = directory.toAbsolutePath().normalize().resolve(safeName(name.isBlank() ? "歌单" : name) + ".m3u8");
        var text = new StringBuilder("#EXTM3U\n");
        for (var result : usable) {
            var metadata = result.metadata();
            text.append("#EXTINF:").append(metadata.durationMillis() > 0 ? metadata.durationMillis() / 1000 : -1)
                    .append(',').append((metadata.artistText() + " - " + metadata.title()).replaceAll("[\\r\\n]", " ")).append('\n');
            try { text.append("./").append(desired.getParent().relativize(result.file().toAbsolutePath()).toString().replace('\\', '/')); }
            catch (IllegalArgumentException exception) { text.append(result.file().toAbsolutePath().toString().replace('\\', '/')); }
            text.append('\n');
        }
        Files.createDirectories(desired.getParent());
        var temporary = Files.createTempFile(desired.getParent(), ".ncm-breaker-output-", ".tmp");
        try {
            Files.writeString(temporary, text, StandardCharsets.UTF_8);
            return commit(temporary, desired, policy, List.of(), new DownloadCancellation()).path();
        } finally { Files.deleteIfExists(temporary); }
    }
}
