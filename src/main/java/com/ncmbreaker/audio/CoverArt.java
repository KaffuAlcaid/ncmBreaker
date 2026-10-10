package com.ncmbreaker.audio;

import com.ncmbreaker.download.AudioDownloader;
import com.ncmbreaker.download.DownloadCancellation;
import com.ncmbreaker.netease.music.MusicException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

public final class CoverArt {
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private CoverArt() { }

    public static byte[] load(String url, DownloadCancellation cancellation) throws Exception {
        cancellation.check();
        if (url.isBlank()) return new byte[0];
        var endpoint = AudioDownloader.endpoint(URI.create(url));
        var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(15)).GET().build();
        var future = HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        cancellation.bind(() -> future.cancel(true));
        HttpResponse<java.io.InputStream> response;
        try { response = future.get(20, TimeUnit.SECONDS); }
        catch (Exception exception) { future.cancel(true); throw exception; }
        try (var input = response.body()) {
            cancellation.bind(input);
            if (response.statusCode() != 200) throw new MusicException("专辑封面暂时无法下载。");
            var reader = java.util.concurrent.Executors.newSingleThreadExecutor(task -> {
                var thread = new Thread(task, "album-cover"); thread.setDaemon(true); return thread;
            });
            try {
                var data = reader.submit(() -> input.readNBytes(MAX_BYTES + 1));
                byte[] bytes;
                try { bytes = data.get(15, TimeUnit.SECONDS); }
                catch (Exception exception) { data.cancel(true); throw exception; }
                cancellation.check();
                if (bytes.length > MAX_BYTES) throw new MusicException("专辑封面文件过大。");
                return bytes;
            } finally { reader.shutdownNow(); }
        }
    }
}
