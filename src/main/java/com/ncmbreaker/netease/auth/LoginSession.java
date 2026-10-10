package com.ncmbreaker.netease.auth;

import com.google.gson.JsonObject;
import com.ncmbreaker.netease.http.NeteaseClient;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public final class LoginSession implements AutoCloseable {
    public static final class ExpiredException extends IOException {
        ExpiredException() { super("登录状态已失效，请重新扫码。"); }
    }
    public enum QrStatus { EXPIRED, WAITING, SCANNED, AUTHORIZED }

    public record Challenge(String key, String url) {
    }

    public record Account(long userId, String nickname, int vipType) {
    }

    private final NeteaseClient client = new NeteaseClient();
    private final SavedLoginStore savedLogin = new SavedLoginStore();
    private volatile Account account;

    public NeteaseClient client() {
        return client;
    }

    public boolean restoreSaved() throws IOException {
        var state = savedLogin.load();
        if (state == null) return false;
        client.restoreSession(state);
        return true;
    }

    public boolean remember() throws IOException {
        return client.rememberSession(savedLogin::save);
    }

    public static void forgetSaved() throws IOException {
        new SavedLoginStore().clear();
    }

    public Challenge createChallenge() throws IOException, InterruptedException {
        var parameters = new JsonObject();
        parameters.addProperty("type", 3);
        var response = client.post("/api/login/qrcode/unikey", parameters, NeteaseClient.Protocol.EAPI);
        requireSuccess(response, "二维码申请失败");
        var data = object(response, "data");
        var key = string(response.has("unikey") ? response : data, "unikey", "");
        if (key.isBlank()) {
            throw new IOException("服务器未返回二维码，请重试。");
        }
        return new Challenge(key, "https://music.163.com/login?codekey="
                + URLEncoder.encode(key, StandardCharsets.UTF_8));
    }

    public QrStatus check(Challenge challenge) throws IOException, InterruptedException {
        var parameters = new JsonObject();
        parameters.addProperty("key", challenge.key());
        parameters.addProperty("type", 3);
        var response = client.post("/api/login/qrcode/client/login", parameters, NeteaseClient.Protocol.EAPI);
        var code = integer(response, "code", -1);
        return switch (code) {
            case 800 -> QrStatus.EXPIRED;
            case 801 -> QrStatus.WAITING;
            case 802 -> QrStatus.SCANNED;
            case 803 -> QrStatus.AUTHORIZED;
            default -> throw new IOException("暂时无法读取登录状态（" + code + "），请刷新二维码。");
        };
    }

    public Account verifyAccount() throws IOException, InterruptedException {
        var response = client.post("/api/w/nuser/account/get", new JsonObject(), NeteaseClient.Protocol.WEAPI);
        if (integer(response, "code", -1) == 301 || integer(response, "code", -1) == 302) throw new ExpiredException();
        requireSuccess(response, "账号验证失败");
        var profile = object(response, "profile");
        var accountData = object(response, "account");
        var id = number(profile, "userId", number(accountData, "id", 0));
        if (id <= 0 || accountData == null) {
            throw new ExpiredException();
        }
        account = new Account(id, string(profile, "nickname", "网易云用户"), integer(profile, "vipType", 0));
        return account;
    }

    public Account account() {
        return account;
    }

    @Override
    public void close() {
        client.close();
        account = null;
    }

    private static void requireSuccess(JsonObject response, String message) throws IOException {
        var code = integer(response, "code", -1);
        if (code != 200) {
            throw new IOException(message + "（" + code + "），请稍后重试。");
        }
    }

    private static JsonObject object(JsonObject value, String key) {
        return value != null && value.has(key) && value.get(key).isJsonObject() ? value.getAsJsonObject(key) : null;
    }

    private static String string(JsonObject value, String key, String fallback) {
        return value != null && value.has(key) && value.get(key).isJsonPrimitive() ? value.get(key).getAsString() : fallback;
    }

    private static long number(JsonObject value, String key, long fallback) {
        try {
            return value != null && value.has(key) && !value.get(key).isJsonNull() ? value.get(key).getAsLong() : fallback;
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    private static int integer(JsonObject value, String key, int fallback) {
        return (int) number(value, key, fallback);
    }
}
