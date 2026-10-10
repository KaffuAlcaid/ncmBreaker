package com.ncmbreaker.netease.music;

import java.net.URI;
import java.util.List;

public final class MusicModels {
    private MusicModels() { }

    public enum Category {
        ALL("全部歌单"), LIKED("喜欢的音乐"), CREATED("创建的歌单"), COLLECTED("收藏的歌单");
        private final String label;
        Category(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    public record Playlist(long id, String name, int trackCount, String coverUrl, Category category) {
        @Override public String toString() { return name + "  (" + trackCount + ")"; }
    }

    public record Song(long id, String title, List<String> artists, String album, long albumId,
                       String coverUrl, long durationMillis, int trackNumber, boolean detailAvailable) {
        public Song { artists = List.copyOf(artists); }
        public String artistText() { return String.join(" / ", artists); }
        public static Song missing(long id) {
            return new Song(id, "歌曲 " + id, List.of(), "", 0, "", 0, 0, false);
        }
    }

    public record PlaylistContent(Playlist playlist, List<Song> songs, int expectedCount) {
        public PlaylistContent { songs = List.copyOf(songs); }
    }

    public record QualityOption(AudioQuality quality, long bytes, int sampleRate, int bitrate) { }

    public record AuthorizedDownload(long songId, AudioQuality requested, AudioQuality actual,
                                     URI url, long bytes, String md5, String declaredFormat) { }
}
