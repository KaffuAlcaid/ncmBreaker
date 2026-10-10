package com.ncmbreaker.playlist;

import com.ncmbreaker.netease.music.MusicException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

public record MusicLink(long id, boolean song) {
    public static MusicLink parse(String input) throws MusicException {
        var text = input.strip().replace("\\&", "&");
        try {
            if (text.matches("[1-9][0-9]*")) return new MusicLink(Long.parseLong(text), false);
            var uri = URI.create(text);
            if (!"music.163.com".equalsIgnoreCase(uri.getHost())) throw new IllegalArgumentException();
            var path = uri.getPath();
            var query = uri.getRawQuery();
            if (uri.getRawFragment() != null) {
                var fragment = URI.create(uri.getRawFragment());
                path = fragment.getPath();
                query = fragment.getRawQuery();
            }
            boolean song = "/song".equals(path);
            if (!song && !"/playlist".equals(path)) throw new IllegalArgumentException();
            for (var part : query == null ? new String[0] : query.split("&")) {
                var pair = part.split("=", 2);
                if (pair.length == 2 && "id".equals(pair[0])) {
                    long id = Long.parseLong(URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
                    if (id > 0) return new MusicLink(id, song);
                }
            }
        } catch (IllegalArgumentException ignored) {
            // Present one actionable message for malformed or unrelated links.
        }
        throw new MusicException("请输入网易云歌单 ID、歌单链接或歌曲链接。");
    }
}
