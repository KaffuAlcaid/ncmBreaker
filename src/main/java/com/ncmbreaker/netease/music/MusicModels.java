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

    public enum CollectionType {
        PLAYLIST("歌单"), ALBUM("专辑");
        private final String label;
        CollectionType(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    public record PlaylistContent(Playlist playlist, List<Song> songs, int expectedCount, CollectionType type) {
        public PlaylistContent { songs = List.copyOf(songs); }
        public PlaylistContent(Playlist playlist, List<Song> songs, int expectedCount) {
            this(playlist, songs, expectedCount, CollectionType.PLAYLIST);
        }
    }

    public enum SearchType {
        SONG(1, "歌曲"), PLAYLIST(1000, "歌单"), ALBUM(10, "专辑");
        private final int code;
        private final String label;
        SearchType(int code, String label) { this.code = code; this.label = label; }
        public int code() { return code; }
        @Override public String toString() { return label; }
    }

    public record PlaylistHit(long id, String name, String creator, int trackCount, String coverUrl) { }
    public record AlbumHit(long id, String name, String artist, int trackCount, String coverUrl) { }

    public record SearchResult(SearchType type, String query, int offset, int limit, int total,
                               boolean hasMore, List<Song> songs, List<PlaylistHit> playlists, List<AlbumHit> albums) {
        public SearchResult {
            songs = List.copyOf(songs);
            playlists = List.copyOf(playlists);
            albums = List.copyOf(albums);
        }
        public int size() {
            return switch (type) {
                case SONG -> songs.size();
                case PLAYLIST -> playlists.size();
                case ALBUM -> albums.size();
            };
        }
    }

    public record QualityOption(AudioQuality quality, long bytes, int sampleRate, int bitrate) { }

    public record AuthorizedDownload(long songId, AudioQuality requested, AudioQuality actual,
                                     URI url, long bytes, String md5, String declaredFormat) { }
}
