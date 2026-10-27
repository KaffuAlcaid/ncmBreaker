package com.ncmbreaker.audio;

import com.ncmbreaker.NcmFileIO.ConflictPolicy;
import java.util.Objects;

public record ProcessingOptions(
        boolean tags, boolean cover, boolean enrich, boolean replaceExisting,
        boolean saveLyrics, boolean embedLyrics, boolean translation, boolean romanization,
        boolean exportCover, boolean albumCover, boolean playlist,
        Layout layout, String template, ConflictPolicy conflictPolicy
) {
    public enum Layout {
        CURRENT("保持现状"), SOURCE("保留输入目录结构"), ARTIST_ALBUM("按歌手 / 专辑整理"),
        PLAYLIST("按歌单整理"), CUSTOM("自定义模板");
        private final String label;
        Layout(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    public ProcessingOptions {
        layout = Objects.requireNonNull(layout);
        template = Objects.requireNonNullElse(template, "{artist}/{album}/{track} - {title}").strip();
        conflictPolicy = Objects.requireNonNull(conflictPolicy);
    }

    public static ProcessingOptions defaults() {
        return new ProcessingOptions(true, true, false, false, false, false, false, false,
                false, false, false, Layout.CURRENT, "{artist}/{album}/{track} - {title}", ConflictPolicy.RENAME);
    }

    public boolean needsLyrics() { return saveLyrics || embedLyrics; }
    public boolean needsCover() { return cover || exportCover; }
    public boolean needsTags() { return tags || cover || embedLyrics; }
    public ProcessingOptions withPlaylist(boolean enabled) {
        return new ProcessingOptions(tags, cover, enrich, replaceExisting, saveLyrics, embedLyrics, translation,
                romanization, exportCover, albumCover, enabled, layout, template, conflictPolicy);
    }
    public ProcessingOptions metadataOnly() {
        return new ProcessingOptions(false, false, true, false, false, false, false, false, false, false, false,
                layout, template, conflictPolicy);
    }
}
