package com.ncmbreaker.netease.music;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.ncmbreaker.netease.auth.LoginSession;
import com.ncmbreaker.netease.http.NeteaseClient;
import com.ncmbreaker.netease.music.MusicModels.*;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.IntConsumer;
import static com.ncmbreaker.netease.music.ApiJson.*;

public final class MusicService {
    private final LoginSession session;

    public MusicService(LoginSession session) { this.session = session; }

    public List<Playlist> userPlaylists() throws IOException, InterruptedException {
        var account = session.account();
        if (account == null) throw new MusicException("请先登录网易云账号。");
        var playlists = new LinkedHashMap<Long, Playlist>();
        for (int offset = 0; ; ) {
            var data = new JsonObject();
            data.addProperty("uid", account.userId());
            data.addProperty("offset", offset);
            data.addProperty("limit", 200);
            data.addProperty("includeVideo", false);
            var response = post("/api/user/playlist", data, NeteaseClient.Protocol.WEAPI);
            var page = array(response, "playlist");
            int before = playlists.size();
            for (var element : page) {
                var item = element.getAsJsonObject();
                boolean own = number(object(item, "creator"), "userId") == account.userId();
                var category = own && number(item, "specialType") == 5 ? Category.LIKED
                        : own ? Category.CREATED : Category.COLLECTED;
                var playlist = new Playlist(number(item, "id"), text(item, "name"),
                        (int) number(item, "trackCount"), text(item, "coverImgUrl"), category);
                if (playlist.id() > 0) playlists.putIfAbsent(playlist.id(), playlist);
            }
            if (!bool(response, "more")) break;
            if (page.isEmpty() || playlists.size() == before) {
                throw new MusicException("歌单分页返回不完整，请刷新重试。");
            }
            offset += page.size();
        }
        return List.copyOf(playlists.values());
    }

    public PlaylistContent playlist(long id, IntConsumer progress) throws IOException, InterruptedException {
        var request = new JsonObject();
        request.addProperty("id", id);
        request.addProperty("n", 0);
        request.addProperty("s", 0);
        var response = post("/api/v6/playlist/detail", request, NeteaseClient.Protocol.EAPI);
        var item = object(response, "playlist");
        if (number(item, "id") != id) throw new MusicException("未找到该歌单，或当前账号无法访问。");
        var ids = new ArrayList<Long>();
        for (var track : array(item, "trackIds")) {
            long songId = track.isJsonObject() ? number(track.getAsJsonObject(), "id") : track.getAsLong();
            if (songId > 0) ids.add(songId);
        }
        int count = (int) number(item, "trackCount");
        if (ids.isEmpty() && count > 0) throw new MusicException("服务器没有返回歌单中的歌曲，请刷新重试。");
        var songs = songs(ids, progress);
        return new PlaylistContent(new Playlist(id, text(item, "name"), count,
                text(item, "coverImgUrl"), Category.CREATED), songs, count);
    }

    public List<Song> songs(List<Long> ids, IntConsumer progress) throws IOException, InterruptedException {
        var found = new LinkedHashMap<Long, Song>();
        for (int start = 0; start < ids.size(); start += 200) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            var batch = ids.subList(start, Math.min(start + 200, ids.size()));
            var query = new JsonArray();
            for (long id : batch) {
                var entry = new JsonObject();
                entry.addProperty("id", id);
                query.add(entry);
            }
            var data = new JsonObject();
            data.addProperty("c", query.toString());
            var response = post("/api/v3/song/detail", data, NeteaseClient.Protocol.WEAPI);
            for (var value : array(response, "songs")) {
                var song = parseSong(value.getAsJsonObject());
                found.put(song.id(), song);
            }
            progress.accept(Math.min(start + 200, ids.size()));
        }
        return ids.stream().map(id -> found.getOrDefault(id, Song.missing(id))).toList();
    }

    public List<QualityOption> qualities(long songId) throws IOException, InterruptedException {
        var parameters = new JsonObject();
        parameters.addProperty("songId", songId);
        var response = post("/api/song/music/detail/get", parameters, NeteaseClient.Protocol.EAPI);
        var data = object(response, "data");
        var options = new ArrayList<QualityOption>();
        for (var quality : AudioQuality.values()) {
            var resource = object(data, quality.resourceKey());
            if (quality == AudioQuality.SKY) {
                for (var alternative : array(data, "sks")) {
                    if ("c51".equals(text(alternative.getAsJsonObject(), "it"))) {
                        resource = alternative.getAsJsonObject();
                        break;
                    }
                }
            }
            if (number(resource, "size") > 0) {
                options.add(new QualityOption(quality, number(resource, "size"),
                        (int) number(resource, "sr"), (int) number(resource, "br")));
            }
        }
        if (options.isEmpty()) throw new MusicException("这首歌暂时没有可用的音质信息。");
        return List.copyOf(options);
    }

    public AuthorizedDownload authorize(long id, AudioQuality quality) throws IOException, InterruptedException {
        if (session.account() == null) throw new MusicException("请先登录网易云账号。");
        var request = new JsonObject();
        request.addProperty("id", id);
        request.addProperty("level", quality.level());
        request.addProperty("immerseType", "c51");
        var profile = quality == AudioQuality.VIVID ? NeteaseClient.ClientProfile.AUDIO_VIVID
                : NeteaseClient.ClientProfile.DESKTOP;
        var response = session.client().post("/api/song/enhance/download/url/v1", request,
                NeteaseClient.Protocol.EAPI, profile);
        success(response);
        var data = object(response, "data");
        if (number(data, "id") != id || number(data, "code") != 200 || text(data, "url").isBlank()) {
            throw new MusicException("当前账号无法下载这首歌的所选音质。");
        }
        for (var key : List.of("freeTrialInfo", "trialInfo")) {
            if (data.has(key) && !data.get(key).isJsonNull()) {
                throw new MusicException("服务器只提供试听内容，请选择其他音质或检查账号权限。");
            }
        }
        var actual = AudioQuality.fromLevel(text(data, "level"));
        if (actual == null || number(data, "size") <= 0) throw new MusicException("服务器返回的下载信息不完整。");
        try {
            return new AuthorizedDownload(id, quality, actual, URI.create(text(data, "url")),
                    number(data, "size"), text(data, "md5"), text(data, "type"));
        } catch (IllegalArgumentException exception) {
            throw new MusicException("服务器返回的下载地址无效。");
        }
    }

    private JsonObject post(String path, JsonObject data, NeteaseClient.Protocol protocol) throws IOException, InterruptedException {
        if (session.account() == null) throw new MusicException("请先登录网易云账号。");
        var response = session.client().post(path, data, protocol);
        success(response);
        return response;
    }

    private static Song parseSong(JsonObject item) {
        var artists = new ArrayList<String>();
        for (var artist : array(item, "ar")) artists.add(text(artist.getAsJsonObject(), "name"));
        var album = object(item, "al");
        return new Song(number(item, "id"), text(item, "name"), artists,
                text(album, "name"), number(album, "id"), text(album, "picUrl"),
                number(item, "dt"), (int) number(item, "no"), true);
    }
}
