package com.ncmbreaker.audio;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ncmbreaker.NcmDecoder;
import com.ncmbreaker.netease.music.MusicModels.Song;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record TrackMetadata(long songId, String title, List<String> artists, long albumId, String album,
                            List<String> albumArtists, int track, int disc, String year,
                            String coverUrl, long durationMillis) {
    public TrackMetadata {
        title = clean(title); album = clean(album); year = clean(year); coverUrl = clean(coverUrl);
        artists = names(artists); albumArtists = names(albumArtists);
        songId = Math.max(0, songId); albumId = Math.max(0, albumId);
        track = Math.max(0, track); disc = Math.max(0, disc); durationMillis = Math.max(0, durationMillis);
    }

    public static TrackMetadata fromSong(Song song) {
        return new TrackMetadata(song.id(), song.detailAvailable() ? song.title() : "", song.artists(), song.albumId(), song.album(),
                List.of(), song.trackNumber(), 0, "", song.coverUrl(), song.durationMillis());
    }

    public static TrackMetadata fromNcm(NcmDecoder.Metadata metadata) {
        var json = JsonParser.parseString(metadata.rawJson()).getAsJsonObject();
        return new TrackMetadata(number(json, "musicId"), metadata.title(), metadata.artists(),
                number(json, "albumId"), metadata.album(), artistNames(json.get("albumArtist")),
                positiveInt(json, "trackNumber", "no"), positiveInt(json, "discNumber", "cd"),
                year(json), text(json, "albumPic"), number(json, "duration"));
    }

    public static TrackMetadata fromSongJson(JsonObject item) {
        var album = object(item, "al");
        return new TrackMetadata(number(item, "id"), text(item, "name"), artistNames(item.get("ar")),
                number(album, "id"), text(album, "name"), List.of(), positiveInt(item, "no", "trackNumber"),
                positiveInt(item, "cd", "discNumber"), year(item), text(album, "picUrl"), number(item, "dt"));
    }

    public TrackMetadata fillMissing(TrackMetadata other) {
        if (other == null) return this;
        return new TrackMetadata(songId > 0 ? songId : other.songId, title.isBlank() ? other.title : title,
                artists.isEmpty() ? other.artists : artists, albumId > 0 ? albumId : other.albumId,
                album.isBlank() ? other.album : album, albumArtists.isEmpty() ? other.albumArtists : albumArtists,
                track > 0 ? track : other.track, disc > 0 ? disc : other.disc,
                year.isBlank() ? other.year : year, coverUrl.isBlank() ? other.coverUrl : coverUrl,
                durationMillis > 0 ? durationMillis : other.durationMillis);
    }

    public String artistText() { return String.join(" / ", artists); }
    public String albumArtistText() { return String.join(" / ", albumArtists); }
    private static String clean(String value) { return Objects.requireNonNullElse(value, "").strip(); }
    private static List<String> names(List<String> values) {
        return values == null ? List.of() : values.stream().filter(Objects::nonNull).map(String::strip)
                .filter(value -> !value.isEmpty()).distinct().toList();
    }
    public static String text(JsonObject object, String key) {
        var value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }
    public static long number(JsonObject object, String key) {
        try { return Long.parseLong(text(object, key)); } catch (NumberFormatException ignored) { return 0; }
    }
    public static JsonObject object(JsonObject object, String key) {
        var value = object.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
    }
    public static int positiveInt(JsonObject object, String... keys) {
        for (var key : keys) {
            long value = number(object, key);
            if (value > 0 && value <= Integer.MAX_VALUE) return (int) value;
        }
        return 0;
    }
    public static String year(JsonObject object) {
        var year = text(object, "year");
        if (year.matches("[12][0-9]{3}")) return year;
        long published = number(object, "publishTime");
        if (published <= 0) return "";
        try { return Integer.toString(Instant.ofEpochMilli(published).atZone(ZoneOffset.UTC).getYear()); }
        catch (RuntimeException ignored) { return ""; }
    }
    public static List<String> artistNames(JsonElement value) {
        var result = new ArrayList<String>();
        if (value == null || value.isJsonNull()) return List.of();
        if (value.isJsonPrimitive()) return names(List.of(value.getAsString()));
        if (value.isJsonObject()) return names(List.of(text(value.getAsJsonObject(), "name")));
        for (var element : value.getAsJsonArray()) {
            if (element.isJsonArray() && !element.getAsJsonArray().isEmpty()) {
                var first = element.getAsJsonArray().get(0);
                if (first.isJsonPrimitive()) result.add(first.getAsString());
            } else result.addAll(artistNames(element));
        }
        return names(result);
    }
}
