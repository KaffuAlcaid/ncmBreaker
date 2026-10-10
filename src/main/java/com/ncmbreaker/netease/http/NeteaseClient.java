package com.ncmbreaker.netease.http;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ncmbreaker.netease.crypto.NeteaseCrypto;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.zip.GZIPInputStream;

public final class NeteaseClient implements AutoCloseable {
    @FunctionalInterface public interface SessionWriter { void save(SessionState state) throws IOException; }
    public enum Protocol { EAPI, WEAPI }
    public enum ClientProfile { DESKTOP, AUDIO_VIVID }

    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final String EAPI_DOMAIN = "https://interfacepc.music.163.com";
    private static final String WEB_DOMAIN = "https://music.163.com";
    private static final String WEB_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0.0.0 Safari/537.36";
    private static final String CLIENT_AGENT = "NeteaseMusic 9.0.90/5038 (iPhone; iOS 16.2; zh_CN)";
    private static final ExecutorService RESPONSE_READERS = Executors.newCachedThreadPool(task -> {
        var thread = new Thread(task, "netease-response");
        thread.setDaemon(true);
        return thread;
    });

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(12))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final SessionCookies cookieStore = new SessionCookies();
    private final CookieManager cookies = new CookieManager(cookieStore, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
    private String deviceId = UUID.randomUUID().toString().replace("-", "");
    private SessionWriter sessionWriter;
    private boolean closed;

    public JsonObject post(String path, JsonObject parameters, Protocol protocol) throws IOException, InterruptedException {
        return post(path, parameters, protocol, ClientProfile.DESKTOP);
    }

    public JsonObject post(String path, JsonObject parameters, Protocol protocol, ClientProfile profile) throws IOException, InterruptedException {
        if (!path.startsWith("/api/") || path.contains("?") || path.contains("#")) {
            throw new IllegalArgumentException("Expected an API path without a query string.");
        }
        var endpoint = URI.create((protocol == Protocol.EAPI ? EAPI_DOMAIN + "/eapi/" : WEB_DOMAIN + "/weapi/")
                + path.substring(5));
        var payload = parameters.deepCopy();
        payload.addProperty("e_r", false);
        var cookieValues = cookieValues(endpoint);
        var requestBuilder = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                .header("Accept", "application/json")
                .header("Accept-Encoding", "gzip");
        final String form;
        try {
            if (protocol == Protocol.EAPI) {
                var header = clientHeader(cookieValues, profile);
                payload.add("header", JSON.toJsonTree(header));
                requestBuilder.header("Cookie", encodeCookies(header)).header("User-Agent", CLIENT_AGENT);
                form = NeteaseCrypto.eapi(path, JSON.toJson(payload));
            } else {
                payload.addProperty("csrf_token", cookieValues.getOrDefault("__csrf", ""));
                cookieValues.putIfAbsent("os", "pc");
                cookieValues.putIfAbsent("appver", "3.1.17.204416");
                if (!cookieValues.isEmpty()) {
                    requestBuilder.header("Cookie", encodeCookies(cookieValues));
                }
                requestBuilder.header("Referer", WEB_DOMAIN + "/").header("User-Agent", WEB_AGENT);
                form = NeteaseCrypto.weapi(JSON.toJson(payload));
            }
        } catch (GeneralSecurityException exception) {
            throw new IOException("无法创建登录请求。", exception);
        }
        var request = requestBuilder.POST(HttpRequest.BodyPublishers.ofString(form)).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        var status = response.statusCode();
        var responseHeaders = response.headers().map();
        JsonElement responseBody;
        try (var raw = response.body()) {
            var bytes = readResponse(raw, response.headers().firstValue("Content-Encoding").orElse(""));
            try {
                responseBody = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            } catch (RuntimeException exception) {
                throw new IOException("服务器未返回有效数据（HTTP " + status + "）。", exception);
            }
        }
        synchronized (this) {
            ensureOpen();
            cookies.put(endpoint, responseHeaders);
            if (sessionWriter != null && responseHeaders.keySet().stream().anyMatch(name -> name.equalsIgnoreCase("set-cookie"))) {
                persistSession();
            }
        }
        if (status < 200 || status >= 300) {
            throw new IOException("请求失败（HTTP " + status + "），请稍后重试。");
        }
        if (!responseBody.isJsonObject()) {
            throw new IOException("服务器返回的数据格式无法识别。");
        }
        return responseBody.getAsJsonObject();
    }

    private static byte[] readResponse(java.io.InputStream raw, String encoding) throws IOException, InterruptedException {
        var future = RESPONSE_READERS.submit(() -> {
            try (var input = encoding.equalsIgnoreCase("gzip") ? new GZIPInputStream(raw) : raw) {
                var bytes = input.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (bytes.length > MAX_RESPONSE_BYTES) throw new IOException("服务器响应过大，请稍后重试。");
                return bytes;
            }
        });
        try {
            return future.get(20, TimeUnit.SECONDS);
        } catch (TimeoutException exception) {
            throw new HttpTimeoutException("读取服务器响应超时。");
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof IOException failure) throw failure;
            throw new IOException("服务器返回的数据无法读取。", exception.getCause());
        } finally {
            future.cancel(true);
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        sessionWriter = null;
        cookies.getCookieStore().removeAll();
    }

    public synchronized void restoreSession(SessionState state) throws IOException {
        ensureOpen();
        try {
            cookieStore.restore(state.cookies());
            deviceId = state.deviceId();
        } catch (RuntimeException exception) {
            cookieStore.removeAll();
            throw new IOException("保存的登录状态无法读取，请重新扫码。");
        }
    }

    public synchronized boolean rememberSession(SessionWriter writer) throws IOException {
        ensureOpen();
        sessionWriter = writer;
        return persistSession();
    }

    private boolean persistSession() {
        try {
            sessionWriter.save(new SessionState(1, deviceId, cookieStore.snapshot()));
            return true;
        } catch (IOException exception) {
            return false;
        }
    }

    private synchronized Map<String, String> cookieValues(URI endpoint) throws IOException {
        ensureOpen();
        var values = new LinkedHashMap<String, String>();
        for (var cookie : cookies.getCookieStore().get(endpoint)) {
            if (!cookie.hasExpired() && matchesPath(endpoint.getPath(), cookie.getPath())) {
                values.put(cookie.getName(), cookie.getValue());
            }
        }
        return values;
    }

    private static boolean matchesPath(String requestPath, String cookiePath) {
        var path = cookiePath == null || cookiePath.isEmpty() ? "/" : cookiePath;
        return requestPath.equals(path) || requestPath.startsWith(path)
                && (path.endsWith("/") || requestPath.charAt(path.length()) == '/');
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("登录会话已结束。");
        }
    }

    private Map<String, String> clientHeader(Map<String, String> values, ClientProfile profile) {
        var header = new LinkedHashMap<String, String>();
        header.put("osver", "Microsoft-Windows-10-Professional-build-19045-64bit");
        header.put("deviceId", deviceId);
        header.put("os", "pc");
        header.put("appver", "3.1.17.204416");
        header.put("versioncode", "140");
        header.put("mobilename", "");
        header.put("buildver", Long.toString(System.currentTimeMillis() / 1000));
        header.put("resolution", "1920x1080");
        header.put("__csrf", values.getOrDefault("__csrf", ""));
        header.put("channel", "netease");
        if (profile == ClientProfile.AUDIO_VIVID) {
            header.put("os", "android");
            header.put("appver", "9.5.61");
            header.put("osver", "14");
            header.put("channel", "xiaomi");
        }
        header.put("requestId", System.currentTimeMillis() + "_" + String.format("%04d", ThreadLocalRandom.current().nextInt(1000)));
        for (var key : List.of("MUSIC_U", "MUSIC_A", "NMTID")) {
            if (values.containsKey(key)) {
                header.put(key, values.get(key));
            }
        }
        return header;
    }

    private static String encodeCookies(Map<String, String> values) {
        return values.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(java.util.stream.Collectors.joining("; "));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
