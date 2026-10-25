package com.ncmbreaker.playlist;

import com.ncmbreaker.netease.music.MusicException;
import com.ncmbreaker.netease.music.MusicModels.SearchType;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

public record MusicLink(long id, SearchType type) {
    public boolean song() { return type == SearchType.SONG; }

    public static MusicLink parse(String input) throws MusicException {
        return parse(input, SearchType.PLAYLIST);
    }

    public static MusicLink parse(String input, boolean songId) throws MusicException {
        return parse(input, songId ? SearchType.SONG : SearchType.PLAYLIST);
    }

    public static MusicLink parse(String input, SearchType defaultType) throws MusicException {
        var text = input.strip().replace("\\&", "&");
        try {
            if (text.matches("[1-9][0-9]*")) return new MusicLink(Long.parseLong(text), defaultType);
            var uri = URI.create(text);
            if (!"music.163.com".equalsIgnoreCase(uri.getHost())) throw new IllegalArgumentException();
            var path = uri.getPath();
            var query = uri.getRawQuery();
            if (uri.getRawFragment() != null) {
                var fragment = URI.create(uri.getRawFragment());
                path = fragment.getPath();
                query = fragment.getRawQuery();
            }
            var type = switch (path == null ? "" : path) {
                case "/song" -> SearchType.SONG;
                case "/playlist" -> SearchType.PLAYLIST;
                case "/album" -> SearchType.ALBUM;
                default -> throw new IllegalArgumentException();
            };
            for (var part : query == null ? new String[0] : query.split("&")) {
                var pair = part.split("=", 2);
                if (pair.length == 2 && "id".equals(pair[0])) {
                    long id = Long.parseLong(URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
                    if (id > 0) return new MusicLink(id, type);
                }
            }
        } catch (IllegalArgumentException ignored) {
            // Present one actionable message for malformed or unrelated links.
        }
        throw new MusicException("请输入网易云" + defaultType + "链接或" + defaultType + " ID");
    }
}
