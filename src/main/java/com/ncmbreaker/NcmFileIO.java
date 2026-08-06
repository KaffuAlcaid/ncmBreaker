package com.ncmbreaker;

import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.stream.Stream;

public final class NcmFileIO {
    private static final byte[] MAGIC = "CTENFDAM".getBytes(StandardCharsets.US_ASCII);
    private static final int BUFFER_SIZE = 64 * 1024;

    private final NcmDecoder decoder;
    private final Id3TagEditor id3TagEditor;

    public NcmFileIO() {
        this(new NcmDecoder(), new Id3TagEditor());
    }

    NcmFileIO(NcmDecoder decoder, Id3TagEditor id3TagEditor) {
        this.decoder = Objects.requireNonNull(decoder, "decoder");
        this.id3TagEditor = Objects.requireNonNull(id3TagEditor, "id3TagEditor");
    }

    public enum Profile {
        CLASSIC_NCM,
        NOT_NCM,
        DAMAGED,
        UNSUPPORTED_VARIANT
    }

    public enum ConflictPolicy {
        RENAME,
        SKIP,
        OVERWRITE
    }

    public enum DecodeOutcome {
        WRITTEN,
        SKIPPED
    }

    public record DecodeOptions(
            boolean writeBasicTags,
            boolean embedCover,
            ConflictPolicy conflictPolicy
    ) {
        public DecodeOptions {
            conflictPolicy = Objects.requireNonNull(conflictPolicy, "conflictPolicy");
        }

        public static DecodeOptions plain() {
            return new DecodeOptions(false, false, ConflictPolicy.RENAME);
        }
    }

    public record Inspection(
            Path source,
            Profile profile,
            String marker,
            String audioFormat,
            String coverFormat,
            long coverSize,
            long audioSize,
            long sourceSize,
            NcmDecoder.Metadata metadata,
            String detail
    ) {
        public boolean convertible() {
            return profile == Profile.CLASSIC_NCM && !"unknown".equals(audioFormat);
        }
    }

    public record DecodeResult(
            Path source,
            Path output,
            String audioFormat,
            long audioBytes,
            NcmDecoder.Metadata metadata,
            DecodeOutcome outcome
    ) {
    }

    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(long completedBytes, long totalBytes);
    }

    public Set<Path> collectNcmFiles(List<Path> inputs) throws IOException {
        var result = new LinkedHashSet<Path>();
        for (var input : inputs) {
            var normalized = normalize(input);
            if (Files.isDirectory(normalized)) {
                try (Stream<Path> paths = Files.walk(normalized)) {
                    paths.filter(Files::isRegularFile)
                            .filter(NcmFileIO::isNcmFile)
                            .map(NcmFileIO::normalize)
                            .forEach(result::add);
                }
            } else if (Files.isRegularFile(normalized) && isNcmFile(normalized)) {
                result.add(normalized);
            }
        }
        return result;
    }

    public Inspection inspect(Path source) {
        var normalized = normalize(source);
        try (var file = new RandomAccessFile(normalized.toFile(), "r")) {
            var parsed = parse(file);
            var audioHeader = readDecryptedAudioHeader(file, parsed);
            var audioFormat = decoder.detectAudioFormat(audioHeader);
            var detail = "unknown".equals(audioFormat)
                    ? "经典容器可解析，但音频负载格式不受支持"
                    : "经典 NCM 容器";
            return new Inspection(
                    normalized,
                    Profile.CLASSIC_NCM,
                    HexFormat.ofDelimiter(" ").withUpperCase().formatHex(parsed.marker()),
                    audioFormat,
                    parsed.coverFormat(),
                    parsed.coverSize(),
                    parsed.audioSize(),
                    file.length(),
                    parsed.metadata(),
                    detail
            );
        } catch (NcmContainerException exception) {
            return failedInspection(normalized, exception.profile(), exception.getMessage());
        } catch (IOException exception) {
            return failedInspection(normalized, Profile.DAMAGED, exception.getMessage());
        }
    }

    public DecodeResult decode(Path source, Path outputDirectory) throws IOException {
        return decode(source, outputDirectory, DecodeOptions.plain(), (completed, total) -> {
        });
    }

    public DecodeResult decode(
            Path source,
            Path outputDirectory,
            ProgressListener progressListener
    ) throws IOException {
        return decode(source, outputDirectory, DecodeOptions.plain(), progressListener);
    }

    public DecodeResult decode(
            Path source,
            Path outputDirectory,
            DecodeOptions options,
            ProgressListener progressListener
    ) throws IOException {
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(progressListener, "progressListener");
        var normalizedSource = normalize(source);
        var normalizedOutputDirectory = normalize(outputDirectory);
        Files.createDirectories(normalizedOutputDirectory);

        Path temporary = null;
        try (var file = new RandomAccessFile(normalizedSource.toFile(), "r")) {
            var parsed = parse(file);
            var audioFormat = decoder.detectAudioFormat(readDecryptedAudioHeader(file, parsed));
            if ("unknown".equals(audioFormat)) {
                throw new IOException("不支持的音频负载格式");
            }

            var target = resolveTarget(normalizedSource, normalizedOutputDirectory, audioFormat, options.conflictPolicy());
            if (target == null) {
                var skippedTarget = baseTarget(normalizedSource, normalizedOutputDirectory, audioFormat);
                return new DecodeResult(normalizedSource, skippedTarget, audioFormat, 0, parsed.metadata(),
                        DecodeOutcome.SKIPPED);
            }
            temporary = Files.createTempFile(normalizedOutputDirectory, ".ncm-breaker-", ".part");
            var cipher = decoder.newAudioCipher(parsed.streamKey());
            var buffer = new byte[BUFFER_SIZE];
            long completed = 0;

            progressListener.onProgress(0, parsed.audioSize());
            try (var output = new BufferedOutputStream(Files.newOutputStream(temporary))) {
                if ("mp3".equals(audioFormat) && (options.writeBasicTags() || options.embedCover())) {
                    var existingTag = readExistingId3(file, parsed, cipher);
                    var cover = options.embedCover() ? readCover(file, parsed) : new byte[0];
                    var rewrittenTag = id3TagEditor.rewrite(
                            existingTag,
                            parsed.metadata(),
                            options.writeBasicTags(),
                            cover,
                            parsed.coverFormat()
                    );
                    output.write(rewrittenTag);
                    completed = existingTag.length;
                }
                file.seek(parsed.audioOffset() + completed);
                progressListener.onProgress(completed, parsed.audioSize());
                int read;
                while ((read = file.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new CancellationException("转换已取消");
                    }
                    cipher.apply(buffer, read, completed);
                    output.write(buffer, 0, read);
                    completed += read;
                    progressListener.onProgress(completed, parsed.audioSize());
                }
            }

            if (completed != parsed.audioSize()) {
                throw new EOFException("音频数据长度与容器记录不一致");
            }

            if (options.conflictPolicy() == ConflictPolicy.OVERWRITE) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(temporary, target);
            }
            temporary = null;
            return new DecodeResult(normalizedSource, target, audioFormat, completed, parsed.metadata(),
                    DecodeOutcome.WRITTEN);
        } catch (CancellationException exception) {
            throw exception;
        } catch (NcmContainerException exception) {
            throw new IOException(exception.getMessage(), exception);
        } finally {
            if (temporary != null) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private byte[] readExistingId3(
            RandomAccessFile file,
            ParsedNcm parsed,
            NcmDecoder.AudioCipher cipher
    ) throws IOException, NcmContainerException {
        if (parsed.audioSize() < 10) {
            return new byte[0];
        }
        file.seek(parsed.audioOffset());
        var header = new byte[10];
        file.readFully(header);
        cipher.apply(header, header.length, 0);
        if (header[0] != 'I' || header[1] != 'D' || header[2] != '3') {
            return new byte[0];
        }
        var bodySize = ((header[6] & 0x7f) << 21)
                | ((header[7] & 0x7f) << 14)
                | ((header[8] & 0x7f) << 7)
                | (header[9] & 0x7f);
        var totalSize = 10L + bodySize;
        if (totalSize > parsed.audioSize() || totalSize > Integer.MAX_VALUE) {
            throw damaged("ID3 标签长度超出音频范围");
        }
        var tag = new byte[(int) totalSize];
        System.arraycopy(header, 0, tag, 0, header.length);
        if (bodySize > 0) {
            file.readFully(tag, 10, bodySize);
            cipher.apply(tag, 10, bodySize, 10);
        }
        return tag;
    }

    private static byte[] readCover(RandomAccessFile file, ParsedNcm parsed) throws IOException {
        if (parsed.coverSize() == 0 || "unknown".equals(parsed.coverFormat())) {
            return new byte[0];
        }
        if (parsed.coverSize() > Integer.MAX_VALUE) {
            throw new IOException("封面图片过大，无法写入标签");
        }
        var cover = new byte[(int) parsed.coverSize()];
        file.seek(parsed.coverOffset());
        file.readFully(cover);
        return cover;
    }

    private ParsedNcm parse(RandomAccessFile file) throws IOException, NcmContainerException {
        if (file.length() < 10) {
            throw damaged("文件短于 NCM 头部");
        }

        file.seek(0);
        var header = new byte[10];
        file.readFully(header);
        for (var index = 0; index < MAGIC.length; index++) {
            if (header[index] != MAGIC[index]) {
                throw new NcmContainerException(Profile.NOT_NCM, "固定标识不是 CTENFDAM");
            }
        }
        var marker = new byte[]{header[8], header[9]};

        var encryptedKey = readLengthPrefixedBlock(file, "密钥区");
        byte[] streamKey;
        try {
            streamKey = decoder.decodeStreamKey(encryptedKey);
        } catch (NcmDecoder.DecodingException exception) {
            throw unsupported(exception.getMessage(), exception);
        }

        var encryptedMetadata = readLengthPrefixedBlock(file, "元数据区");
        NcmDecoder.Metadata metadata;
        try {
            metadata = decoder.decodeMetadata(encryptedMetadata);
        } catch (NcmDecoder.DecodingException exception) {
            throw unsupported(exception.getMessage(), exception);
        }

        ensureRemaining(file, 13, "CRC、保留区和封面长度");
        file.seek(file.getFilePointer() + 9);
        var coverSize = readUnsignedIntLittleEndian(file);
        ensureRemaining(file, coverSize, "封面区");
        var coverOffset = file.getFilePointer();
        var coverFormat = inspectCoverFormat(file, coverOffset, coverSize);
        var audioOffset = Math.addExact(coverOffset, coverSize);
        var audioSize = file.length() - audioOffset;
        if (audioSize <= 0) {
            throw damaged("容器中没有音频数据");
        }

        return new ParsedNcm(
                marker,
                streamKey,
                metadata,
                coverOffset,
                coverSize,
                coverFormat,
                audioOffset,
                audioSize
        );
    }

    private byte[] readDecryptedAudioHeader(RandomAccessFile file, ParsedNcm parsed) throws IOException {
        var length = (int) Math.min(parsed.audioSize(), 16);
        var header = new byte[length];
        file.seek(parsed.audioOffset());
        file.readFully(header);
        decoder.newAudioCipher(parsed.streamKey()).apply(header, header.length, 0);
        return header;
    }

    private String inspectCoverFormat(RandomAccessFile file, long offset, long length) throws IOException {
        if (length == 0) {
            return "none";
        }
        var originalPosition = file.getFilePointer();
        try {
            file.seek(offset);
            var header = new byte[(int) Math.min(length, 12)];
            file.readFully(header);
            return decoder.detectCoverFormat(header);
        } finally {
            file.seek(originalPosition);
        }
    }

    private static byte[] readLengthPrefixedBlock(RandomAccessFile file, String label)
            throws IOException, NcmContainerException {
        ensureRemaining(file, 4, label + "长度");
        var length = readUnsignedIntLittleEndian(file);
        ensureRemaining(file, length, label);
        if (length > Integer.MAX_VALUE) {
            throw damaged(label + "过大，无法读取到内存");
        }
        var data = new byte[(int) length];
        file.readFully(data);
        return data;
    }

    private static long readUnsignedIntLittleEndian(RandomAccessFile file) throws IOException {
        return Integer.toUnsignedLong(Integer.reverseBytes(file.readInt()));
    }

    private static void ensureRemaining(RandomAccessFile file, long required, String label)
            throws IOException, NcmContainerException {
        if (required < 0 || required > file.length() - file.getFilePointer()) {
            throw damaged(label + "超出文件范围");
        }
    }

    private static Path resolveTarget(
            Path source,
            Path outputDirectory,
            String extension,
            ConflictPolicy conflictPolicy
    ) {
        var target = baseTarget(source, outputDirectory, extension);
        if (!Files.exists(target) || conflictPolicy == ConflictPolicy.OVERWRITE) {
            return target;
        }
        if (conflictPolicy == ConflictPolicy.SKIP) {
            return null;
        }
        var fileName = source.getFileName().toString();
        var baseName = stripNcmExtension(fileName);
        for (var suffix = 1; ; suffix++) {
            var candidate = outputDirectory.resolve(baseName + " (" + suffix + ")." + extension);
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
    }

    private static Path baseTarget(Path source, Path outputDirectory, String extension) {
        var fileName = source.getFileName().toString();
        return outputDirectory.resolve(stripNcmExtension(fileName) + "." + extension);
    }

    private static String stripNcmExtension(String fileName) {
        return fileName.regionMatches(true, Math.max(0, fileName.length() - 4), ".ncm", 0, 4)
                ? fileName.substring(0, fileName.length() - 4)
                : fileName;
    }

    private static boolean isNcmFile(Path path) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".ncm");
    }

    private static Inspection failedInspection(Path source, Profile profile, String detail) {
        return new Inspection(source, profile, "", "unknown", "none", 0, 0, 0, null,
                Objects.requireNonNullElse(detail, "未知错误"));
    }

    private static Path normalize(Path path) {
        return Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
    }

    private static NcmContainerException damaged(String message) {
        return new NcmContainerException(Profile.DAMAGED, message);
    }

    private static NcmContainerException unsupported(String message, Throwable cause) {
        return new NcmContainerException(Profile.UNSUPPORTED_VARIANT, message, cause);
    }

    private record ParsedNcm(
            byte[] marker,
            byte[] streamKey,
            NcmDecoder.Metadata metadata,
            long coverOffset,
            long coverSize,
            String coverFormat,
            long audioOffset,
            long audioSize
    ) {
    }

    private static final class NcmContainerException extends Exception {
        private final Profile profile;

        private NcmContainerException(Profile profile, String message) {
            super(message);
            this.profile = profile;
        }

        private NcmContainerException(Profile profile, String message, Throwable cause) {
            super(message, cause);
            this.profile = profile;
        }

        private Profile profile() {
            return profile;
        }
    }
}
