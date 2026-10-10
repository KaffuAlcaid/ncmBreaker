package com.ncmbreaker.audio;

import com.ncmbreaker.download.DownloadCancellation;
import com.ncmbreaker.netease.music.MusicModels.Song;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.images.ArtworkFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class AudioTagWriter {
    private AudioTagWriter() { }

    public static String write(Path source, String format, Song song, boolean tags, boolean cover,
                               DownloadCancellation cancellation) throws Exception {
        if (!tags && !cover) return "";
        if (!format.equals("flac") && !format.equals("mp3")) return "此格式已保存，标签保持原样";
        var temporary = Files.createTempFile(source.getParent(), ".ncm-breaker-tags-", "." + format);
        Path coverFile = null;
        try {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
            cancellation.check();
            var audio = AudioFileIO.read(temporary.toFile());
            var tag = audio.getTagOrCreateAndSetDefault();
            if (tags) {
                tag.setField(FieldKey.TITLE, song.title());
                if (!song.artists().isEmpty()) tag.setField(FieldKey.ARTIST, song.artists().toArray(String[]::new));
                tag.setField(FieldKey.ALBUM, song.album());
                if (song.trackNumber() > 0) tag.setField(FieldKey.TRACK, Integer.toString(song.trackNumber()));
            }
            String warning = "";
            if (cover) {
                try {
                    var bytes = CoverArt.load(song.coverUrl(), cancellation);
                    cancellation.check();
                    if (bytes.length > 0) {
                        String extension = bytes.length > 3 && (bytes[0] & 255) == 255 && (bytes[1] & 255) == 216 ? ".jpg" : ".png";
                        coverFile = Files.createTempFile(source.getParent(), ".ncm-breaker-cover-", extension);
                        Files.write(coverFile, bytes);
                        var artwork = ArtworkFactory.createArtworkFromFile(coverFile.toFile());
                        tag.deleteArtworkField();
                        tag.setField(artwork);
                    } else warning = "未取得专辑封面";
                } catch (java.util.concurrent.CancellationException exception) { throw exception; }
                catch (Exception exception) { cancellation.check(); warning = "封面写入失败"; }
            }
            cancellation.check();
            audio.commit();
            cancellation.check();
            Files.move(temporary, source, StandardCopyOption.REPLACE_EXISTING);
            return warning;
        } finally {
            Files.deleteIfExists(temporary);
            if (coverFile != null) Files.deleteIfExists(coverFile);
        }
    }
}
