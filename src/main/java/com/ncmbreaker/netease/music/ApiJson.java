package com.ncmbreaker.netease.music;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

final class ApiJson {
    private ApiJson() { }
    static String text(JsonObject object, String key) {
        return object != null && object.has(key) && object.get(key).isJsonPrimitive()
                ? object.get(key).getAsString() : "";
    }
    static long number(JsonObject object, String key) {
        try { return Long.parseLong(text(object, key)); }
        catch (NumberFormatException exception) { return 0; }
    }
    static boolean bool(JsonObject object, String key) {
        return "true".equals(text(object, key));
    }
    static JsonObject object(JsonObject parent, String key) {
        return parent != null && parent.has(key) && parent.get(key).isJsonObject()
                ? parent.getAsJsonObject(key) : new JsonObject();
    }
    static JsonArray array(JsonObject parent, String key) {
        return parent != null && parent.has(key) && parent.get(key).isJsonArray()
                ? parent.getAsJsonArray(key) : new JsonArray();
    }
    static void success(JsonObject response) throws MusicException {
        long code = number(response, "code");
        if (code == 301 || code == 302) throw new MusicException("登录状态已失效，请重新扫码登录。");
        if (code != 200) throw new MusicException("服务器暂时无法提供该内容（" + code + "）。");
    }
}
