package com.ncmbreaker.download;

import com.ncmbreaker.netease.music.MusicException;
import com.ncmbreaker.netease.music.MusicModels.AuthorizedDownload;
import com.ncmbreaker.netease.music.MusicModels.Song;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class AudioDownloader {
    @FunctionalInterface public interface Progress { void update(long completed, long total); }
    public record Result(Path file, String format, String warning) { }
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public Result download(AuthorizedDownload authorization, Song song, Path directory,
                           boolean tags, boolean cover, DownloadCancellation cancellation, Progress progress,
                           java.util.function.Consumer<String> stage) throws Exception {
        cancellation.check();
        Files.createDirectories(directory);
        var temporary = Files.createTempFile(directory, ".ncm-breaker-download-", ".part");
        try {
            fetch(authorization, temporary, cancellation, progress);
            cancellation.check();
            String format;
            try (var input = Files.newInputStream(temporary)) { format = format(input.readNBytes(16)); }
            if (format == null) throw new MusicException("下载内容的音频格式暂不受支持，文件未保存。");
            String warning = "";
            if (tags || cover) {
                stage.accept("正在写入歌曲信息");
                try {
                    warning = com.ncmbreaker.audio.AudioTagWriter.write(temporary, format, song, tags, cover, cancellation);
                } catch (java.util.concurrent.CancellationException exception) { throw exception; }
                catch (Exception exception) { cancellation.check(); warning = "标签写入失败，已保留完整音频"; }
            }
            var base = safeName(song.artistText().isBlank() ? song.title() : song.artistText() + " - " + song.title());
            base += " [" + authorization.actual() + "]";
            for (int number = 0; ; number++) {
                cancellation.check();
                var name = base + (number == 0 ? "" : " (" + number + ")") + "." + format;
                var target = directory.resolve(name);
                try {
                    Files.move(temporary, target);
                    return new Result(target, format, warning);
                } catch (FileAlreadyExistsException ignored) {
                    // Another completed job may have claimed the same filename.
                }
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void fetch(AuthorizedDownload authorization, Path temporary, DownloadCancellation cancellation, Progress progress)
            throws Exception {
        var destination = endpoint(authorization.url());
        for (int redirects = 0; redirects < 6; redirects++) {
            cancellation.check();
            var request = HttpRequest.newBuilder(destination).timeout(Duration.ofSeconds(20))
                    .header("Accept-Encoding", "identity").GET().build();
            var future = HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
            cancellation.bind(() -> future.cancel(true));
            HttpResponse<java.io.InputStream> response;
            try { response = future.get(25, TimeUnit.SECONDS); }
            catch (Exception exception) { future.cancel(true); throw exception; }
            try (var input = response.body()) {
                cancellation.bind(input);
                int status = response.statusCode();
                if (status >= 300 && status < 400) {
                    destination = endpoint(destination.resolve(response.headers().firstValue("Location")
                            .orElseThrow(() -> new MusicException("下载地址跳转失败，请重试。"))));
                    continue;
                }
                if (status == 401 || status == 403 || status == 404) {
                    throw new MusicException("下载地址已失效，请重试以获取新地址。");
                }
                if (status != 200) throw new MusicException("音频服务器返回错误（" + status + "），请重试。");
                var declared = response.headers().firstValueAsLong("Content-Length");
                if (declared.isPresent() && declared.getAsLong() != authorization.bytes()) {
                    throw new MusicException("文件长度与下载信息不一致，请重试。");
                }
                var lastRead = new AtomicLong(System.nanoTime());
                var stalled = new AtomicBoolean();
                var timeout = Executors.newSingleThreadScheduledExecutor(task -> {
                    var thread = new Thread(task, "audio-download-timeout");
                    thread.setDaemon(true);
                    return thread;
                });
                timeout.scheduleAtFixedRate(() -> {
                    if (System.nanoTime() - lastRead.get() > TimeUnit.SECONDS.toNanos(45)) {
                        stalled.set(true);
                        try { input.close(); } catch (IOException ignored) { }
                    }
                }, 5, 5, TimeUnit.SECONDS);
                try (var output = Files.newOutputStream(temporary)) {
                    var digest = MessageDigest.getInstance("MD5");
                    var buffer = new byte[64 * 1024];
                    long completed = 0;
                    long lastUpdate = 0;
                    int length;
                    while ((length = input.read(buffer)) != -1) {
                        cancellation.check();
                        lastRead.set(System.nanoTime());
                        completed += length;
                        if (completed > authorization.bytes()) throw new MusicException("服务器返回的文件超过预期大小。");
                        output.write(buffer, 0, length);
                        digest.update(buffer, 0, length);
                        if (System.nanoTime() - lastUpdate > TimeUnit.MILLISECONDS.toNanos(100)) {
                            progress.update(completed, authorization.bytes());
                            lastUpdate = System.nanoTime();
                        }
                    }
                    cancellation.check();
                    if (stalled.get()) throw new MusicException("下载长时间没有进展，请检查网络后重试。");
                    if (completed != authorization.bytes()) throw new MusicException("音频下载不完整，请重试。");
                    if (!authorization.md5().isBlank()
                            && !HexFormat.of().formatHex(digest.digest()).equalsIgnoreCase(authorization.md5())) {
                        throw new MusicException("文件校验未通过，请重试。");
                    }
                    progress.update(completed, authorization.bytes());
                } catch (IOException exception) {
                    cancellation.check();
                    if (stalled.get()) throw new MusicException("下载长时间没有进展，请检查网络后重试。");
                    throw exception;
                } finally {
                    timeout.shutdownNow();
                }
                return;
            }
        }
        throw new MusicException("下载地址跳转次数过多，请重试。");
    }

    public static URI endpoint(URI uri) throws MusicException {
        var host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!(host.endsWith(".music.126.net") || host.endsWith(".music.163.com") || host.endsWith(".nosdn.127.net"))
                || uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 80 && uri.getPort() != 443)) {
            throw new MusicException("服务器返回了无法识别的音频地址。");
        }
        if ("http".equalsIgnoreCase(uri.getScheme())) return URI.create("https:" + uri.toString().substring(5));
        if (!"https".equalsIgnoreCase(uri.getScheme())) throw new MusicException("下载地址协议不受支持。");
        return uri;
    }

    private static String format(byte[] header) {
        if (header.length >= 8 && new String(header, 0, 8, StandardCharsets.US_ASCII).equals("CTENFDAM")) return "ncm";
        if (header.length >= 4 && new String(header, 0, 4, StandardCharsets.US_ASCII).equals("fLaC")) return "flac";
        if (header.length >= 3 && new String(header, 0, 3, StandardCharsets.US_ASCII).equals("ID3")) return "mp3";
        if (header.length >= 4 && (header[0] & 255) == 255 && (header[1] & 224) == 224
                && (header[1] & 6) != 0 && (header[2] & 240) != 0 && (header[2] & 240) != 240) return "mp3";
        if (header.length >= 12 && new String(header, 4, 4, StandardCharsets.US_ASCII).equals("ftyp")) return "m4a";
        if (header.length >= 4 && new String(header, 0, 4, StandardCharsets.US_ASCII).equals("OggS")) return "ogg";
        return null;
    }

    private static String safeName(String name) {
        var value = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").strip().replaceAll("[. ]+$", "");
        if (value.isBlank()) value = "audio";
        if (value.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])")) value = "_" + value;
        int end = value.offsetByCodePoints(0, Math.min(value.codePointCount(0, value.length()), 90));
        return value.substring(0, end);
    }
}
