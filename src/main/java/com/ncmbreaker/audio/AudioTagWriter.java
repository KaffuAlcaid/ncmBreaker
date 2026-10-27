package com.ncmbreaker.audio;

import com.ncmbreaker.download.DownloadCancellation;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.images.ArtworkFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.CancellationException;
import static com.ncmbreaker.audio.AudioResult.WarningCode.*;

public final class AudioTagWriter {
    @FunctionalInterface public interface CoverSource { byte[] load() throws Exception; }
    private AudioTagWriter() { }

    public static byte[] write(Path source, String format, TrackMetadata metadata, ProcessingOptions options,
                               String lyrics, byte[] containerCover, CoverSource coverSource,
                               DownloadCancellation cancellation, List<AudioResult.Warning> warnings) throws Exception {
        if (!options.needsTags() && !options.exportCover()) return new byte[0];
        byte[] selectedCover = containerCover;
        if (!format.equals("flac") && !format.equals("mp3")) {
            if (options.needsTags()) warnings.add(new AudioResult.Warning(TAGS, "此格式已保存，标签保持原样"));
            if (options.exportCover()) selectedCover = loadCover(containerCover, coverSource, cancellation, warnings);
            return selectedCover;
        }
        var temporary = Files.createTempFile(source.getParent(), ".ncm-breaker-tags-", "." + format);
        Path coverFile = null;
        try {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
            cancellation.check();
            var audio = AudioFileIO.read(temporary.toFile());
            var tag = audio.getTagOrCreateAndSetDefault();
            boolean changed = false;
            if (options.tags()) {
                changed |= set(tag, FieldKey.TITLE, options.replaceExisting(), metadata.title());
                changed |= set(tag, FieldKey.ARTIST, options.replaceExisting(), metadata.artists().toArray(String[]::new));
                changed |= set(tag, FieldKey.ALBUM, options.replaceExisting(), metadata.album());
                changed |= set(tag, FieldKey.ALBUM_ARTIST, options.replaceExisting(), metadata.albumArtists().toArray(String[]::new));
                if (metadata.track() > 0) changed |= set(tag, FieldKey.TRACK, options.replaceExisting(), Integer.toString(metadata.track()));
                if (metadata.disc() > 0) changed |= set(tag, FieldKey.DISC_NO, options.replaceExisting(), Integer.toString(metadata.disc()));
                changed |= set(tag, FieldKey.YEAR, options.replaceExisting(), metadata.year());
            }
            if (options.embedLyrics() && !lyrics.isBlank()) changed |= set(tag, FieldKey.LYRICS, options.replaceExisting(), lyrics);
            if (options.needsCover()) {
                var existing = tag.getFirstArtwork();
                var existingBytes = existing == null || existing.getBinaryData() == null ? new byte[0] : existing.getBinaryData();
                boolean usable = !CoverArt.extension(existingBytes).isEmpty();
                selectedCover = usable && !options.replaceExisting() ? existingBytes
                        : loadCover(containerCover, coverSource, cancellation, warnings);
                if (selectedCover.length == 0 && usable) selectedCover = existingBytes;
                if (options.cover() && selectedCover.length > 0 && (!usable || options.replaceExisting())
                        && !java.util.Arrays.equals(selectedCover, existingBytes)) {
                    coverFile = Files.createTempFile(source.getParent(), ".ncm-breaker-cover-", CoverArt.extension(selectedCover));
                    Files.write(coverFile, selectedCover);
                    var artwork = ArtworkFactory.createArtworkFromFile(coverFile.toFile());
                    artwork.setPictureType(3);
                    tag.deleteArtworkField();
                    tag.setField(artwork);
                    changed = true;
                }
            }
            cancellation.check();
            if (changed) {
                audio.commit();
                cancellation.check();
                Files.move(temporary, source, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (CancellationException exception) { throw exception; }
        catch (Exception exception) {
            cancellation.check();
            warnings.add(new AudioResult.Warning(TAGS, "标签写入失败，已保留完整音频：" + exception.getMessage()));
        } finally {
            Files.deleteIfExists(temporary);
            if (coverFile != null) Files.deleteIfExists(coverFile);
        }
        return selectedCover;
    }

    private static boolean set(Tag tag, FieldKey field, boolean replace, String... values) throws Exception {
        var usable = java.util.Arrays.stream(values).filter(value -> value != null && !value.isBlank()).toArray(String[]::new);
        if (usable.length == 0 || (!replace && !tag.getFirst(field).isBlank())) return false;
        tag.setField(field, usable);
        return true;
    }

    private static byte[] loadCover(byte[] embedded, CoverSource source, DownloadCancellation cancellation,
                                    List<AudioResult.Warning> warnings) {
        if (!CoverArt.extension(embedded).isEmpty()) return embedded;
        try {
            var bytes = source.load();
            cancellation.check();
            if (!CoverArt.extension(bytes).isEmpty()) return bytes;
            warnings.add(new AudioResult.Warning(COVER, bytes.length == 0 ? "未取得专辑封面" : "封面图片格式无法识别"));
        } catch (CancellationException exception) { throw exception; }
        catch (Exception exception) {
            cancellation.check();
            warnings.add(new AudioResult.Warning(COVER, "封面获取失败：" + exception.getMessage()));
        }
        return new byte[0];
    }
}
