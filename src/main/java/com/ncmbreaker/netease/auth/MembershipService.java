package com.ncmbreaker.netease.auth;

import com.google.gson.JsonObject;
import com.ncmbreaker.netease.http.NeteaseClient;
import java.io.IOException;
import java.time.Instant;

public final class MembershipService {
    public enum Type {
        SVIP("redplus", 300, "黑胶 SVIP"),
        BLACK_VIP("associator", 100, "黑胶 VIP"),
        MUSIC_PACKAGE("musicPackage", 220, "音乐包"),
        ALBUM_VIP("albumVip", 400, "专辑 VIP"),
        AUDIOBOOK_VIP("voiceBookVip", 500, "有声书 VIP"),
        NORMAL(null, 0, "普通用户"),
        UNKNOWN(null, 0, "会员信息暂不可用");

        private final String key;
        private final int code;
        private final String label;

        Type(String key, int code, String label) {
            this.key = key; this.code = code; this.label = label;
        }
    }

    public record Info(Type type, int level, Instant expiresAt) {
        public static Info unknown() { return new Info(Type.UNKNOWN, 0, null); }
        public String label() { return type.label + (level > 0 ? " · Lv" + level : ""); }
    }

    private MembershipService() { }

    public static Info fetch(NeteaseClient client, long userId) throws IOException, InterruptedException {
        var parameters = new JsonObject();
        parameters.addProperty("userId", Long.toString(userId));
        var response = client.post("/api/music-vip-membership/client/vip/info", parameters, NeteaseClient.Protocol.WEAPI);
        var data = object(response, "data");
        if (number(response, "code", -1) != 200 || data == null || number(data, "userId", -1) != userId) {
            throw new IOException("会员详情暂不可用。");
        }
        long now = number(data, "now", System.currentTimeMillis());
        if (now <= 0) now = System.currentTimeMillis();
        boolean recognized = number(data, "redVipLevel", -1) == 0;
        boolean incomplete = false;
        for (var type : Type.values()) {
            if (type.key == null) continue;
            var benefit = object(data, type.key);
            if (benefit == null) continue;
            recognized = true;
            long level = number(benefit, "vipLevel", -1);
            if (level == 0) continue;
            long expiresAt = number(benefit, "expireTime", -1);
            if (expiresAt > 0 && expiresAt <= now) continue;
            if (level < 0 || level > Integer.MAX_VALUE || expiresAt <= 0
                    || number(benefit, "vipCode", -1) != type.code) {
                incomplete = true;
                continue;
            }
            // Missing higher-tier data must not silently downgrade the displayed identity.
            if (incomplete) return Info.unknown();
            long displayLevel = type == Type.SVIP || type == Type.BLACK_VIP
                    ? number(data, "redVipLevel", 0) : level;
            return new Info(type, displayLevel > 0 && displayLevel <= Integer.MAX_VALUE ? (int) displayLevel : 0,
                    Instant.ofEpochMilli(expiresAt));
        }
        return recognized && !incomplete ? new Info(Type.NORMAL, 0, null) : Info.unknown();
    }

    private static JsonObject object(JsonObject parent, String key) {
        return parent != null && parent.has(key) && parent.get(key).isJsonObject() ? parent.getAsJsonObject(key) : null;
    }

    private static long number(JsonObject object, String key, long fallback) {
        try {
            return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsLong() : fallback;
        } catch (RuntimeException exception) {
            return fallback;
        }
    }
}
