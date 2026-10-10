package com.ncmbreaker.playlist;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ncmbreaker.netease.music.MusicException;
import com.ncmbreaker.netease.music.MusicModels.*;
import org.apache.commons.csv.CSVFormat;
import java.io.IOException;
import java.io.PushbackReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class PlaylistImport {
    private PlaylistImport() { }

    public static PlaylistContent read(Path path) throws IOException {
        if (Files.size(path) > 32L * 1024 * 1024) throw new MusicException("歌单文件超过 32 MB。");
        var songs = new ArrayList<Song>();
        String name = path.getFileName().toString();
        long playlistId = 0;
        int expected = -1;
        try (var reader = new PushbackReader(Files.newBufferedReader(path, StandardCharsets.UTF_8), 1)) {
            int first = reader.read();
            if (first != -1 && first != 0xfeff) reader.unread(first);
            if (name.toLowerCase(Locale.ROOT).endsWith(".json")) {
                var root = JsonParser.parseReader(reader).getAsJsonObject();
                if (!root.has("tracks") || !root.get("tracks").isJsonArray()) {
                    throw new MusicException("请选择包含 tracks 的 master.json 歌单文件。");
                }
                if (root.has("playlist") && root.get("playlist").isJsonObject()) {
                    var info = root.getAsJsonObject("playlist");
                    name = value(info, "name", name);
                    playlistId = number(value(info, "id", "0"));
                    expected = (int) number(value(info, "apiTrackCount", "-1"));
                }
                for (var element : root.getAsJsonArray("tracks")) {
                    var row = element.getAsJsonObject();
                    songs.add(song(value(row, "netease_song_id", ""), value(row, "song_name", ""),
                            value(row, "artist_names", ""), value(row, "album_name", ""),
                            value(row, "album_id", "0"), value(row, "duration_ms", "0"),
                            value(row, "status", "ok")));
                }
            } else {
                try (var parser = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true)
                        .setIgnoreEmptyLines(true).get().parse(reader)) {
                    if (!parser.getHeaderMap().containsKey("netease_song_id")) {
                        throw new MusicException("请选择包含 netease_song_id 的 master.csv 歌单文件。");
                    }
                    for (var record : parser) {
                        Map<String, String> row = record.toMap();
                        if (songs.isEmpty()) {
                            name = row.getOrDefault("playlist_name", name);
                            playlistId = number(row.getOrDefault("playlist_id", "0"));
                        }
                        songs.add(song(row.getOrDefault("netease_song_id", ""), row.getOrDefault("song_name", ""),
                                row.getOrDefault("artist_names", ""), row.getOrDefault("album_name", ""),
                                row.getOrDefault("album_id", "0"), row.getOrDefault("duration_ms", "0"),
                                row.getOrDefault("status", "ok")));
                    }
                }
            }
        } catch (MusicException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MusicException("歌单文件格式不正确，请检查字段和歌曲 ID。");
        }
        if (expected < 0) expected = songs.size();
        return new PlaylistContent(new Playlist(playlistId, name, songs.size(), "", Category.CREATED), songs, expected);
    }

    private static Song song(String id, String title, String artists, String album, String albumId, String duration, String status)
            throws MusicException {
        long songId = number(id);
        if (songId <= 0) throw new MusicException("歌单中存在无效的歌曲 ID。");
        return new Song(songId, title.isBlank() ? "歌曲 " + songId : title,
                artists.isBlank() ? List.of() : List.of(artists.split(" / ")), album,
                number(albumId), "", number(duration), 0, "ok".equals(status));
    }

    private static String value(JsonObject object, String key, String fallback) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : fallback;
    }

    private static long number(String value) {
        try { return Long.parseLong(value); }
        catch (NumberFormatException exception) { return 0; }
    }
}
