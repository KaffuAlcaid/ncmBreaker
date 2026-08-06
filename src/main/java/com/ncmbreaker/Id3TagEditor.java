package com.ncmbreaker;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

final class Id3TagEditor {
    private static final Set<String> BASIC_TEXT_FRAMES = Set.of("TIT2", "TPE1", "TALB");

    byte[] rewrite(
            byte[] existingTag,
            NcmDecoder.Metadata metadata,
            boolean writeBasicTags,
            byte[] cover,
            String coverFormat
    ) {
        var version = id3Version(existingTag);
        var frames = retainedFrames(existingTag, version, writeBasicTags, cover.length > 0);

        if (writeBasicTags) {
            addTextFrame(frames, "TIT2", metadata.title(), version);
            addTextFrame(frames, "TPE1", metadata.artistDisplay(), version);
            addTextFrame(frames, "TALB", metadata.album(), version);
        }
        if (cover.length > 0) {
            frames.add(frame("APIC", pictureBody(cover, coverFormat), version));
        }

        var bodySize = frames.stream().mapToInt(frame -> frame.length).sum();
        if (bodySize > 0x0fffffff) {
            throw new IllegalArgumentException("ID3 标签过大");
        }

        var output = new ByteArrayOutputStream(10 + bodySize);
        output.writeBytes(new byte[]{'I', 'D', '3', (byte) version, 0, 0});
        output.writeBytes(syncSafe(bodySize));
        frames.forEach(output::writeBytes);
        return output.toByteArray();
    }

    private static int id3Version(byte[] tag) {
        if (tag.length >= 10 && tag[0] == 'I' && tag[1] == 'D' && tag[2] == '3') {
            var version = tag[3] & 0xff;
            if ((version == 3 || version == 4) && tag[5] == 0) {
                return version;
            }
        }
        return 3;
    }

    private static List<byte[]> retainedFrames(
            byte[] tag,
            int outputVersion,
            boolean replaceBasicTags,
            boolean replaceCover
    ) {
        var result = new ArrayList<byte[]>();
        if (tag.length < 10 || tag[0] != 'I' || tag[1] != 'D' || tag[2] != '3'
                || (tag[3] & 0xff) != outputVersion || tag[5] != 0) {
            return result;
        }

        var bodySize = readSyncSafe(tag, 6);
        var end = Math.min(tag.length, 10 + bodySize);
        for (var offset = 10; offset + 10 <= end; ) {
            if (tag[offset] == 0 && tag[offset + 1] == 0 && tag[offset + 2] == 0 && tag[offset + 3] == 0) {
                break;
            }
            var id = new String(tag, offset, 4, StandardCharsets.US_ASCII);
            if (!validFrameId(id)) {
                break;
            }
            var frameSize = outputVersion == 4 ? readSyncSafe(tag, offset + 4) : readInt(tag, offset + 4);
            if (frameSize < 0 || offset + 10L + frameSize > end) {
                break;
            }
            var replace = (replaceBasicTags && BASIC_TEXT_FRAMES.contains(id))
                    || (replaceCover && "APIC".equals(id));
            if (!replace) {
                result.add(Arrays.copyOfRange(tag, offset, offset + 10 + frameSize));
            }
            offset += 10 + frameSize;
        }
        return result;
    }

    private static boolean validFrameId(String id) {
        if (id.length() != 4) {
            return false;
        }
        for (var index = 0; index < id.length(); index++) {
            var character = id.charAt(index);
            if (!(character >= 'A' && character <= 'Z') && !(character >= '0' && character <= '9')) {
                return false;
            }
        }
        return true;
    }

    private static void addTextFrame(List<byte[]> frames, String id, String value, int version) {
        if (value == null || value.isBlank()) {
            return;
        }
        byte[] text;
        byte encoding;
        if (version == 4) {
            encoding = 3;
            text = value.getBytes(StandardCharsets.UTF_8);
        } else {
            encoding = 1;
            text = value.getBytes(StandardCharsets.UTF_16);
        }
        var body = new byte[text.length + 1];
        body[0] = encoding;
        System.arraycopy(text, 0, body, 1, text.length);
        frames.add(frame(id, body, version));
    }

    private static byte[] pictureBody(byte[] cover, String coverFormat) {
        var mime = switch (coverFormat) {
            case "png" -> "image/png";
            case "jpeg" -> "image/jpeg";
            default -> "application/octet-stream";
        };
        var mimeBytes = mime.getBytes(StandardCharsets.US_ASCII);
        var body = new byte[1 + mimeBytes.length + 1 + 1 + 1 + cover.length];
        var offset = 0;
        body[offset++] = 0;
        System.arraycopy(mimeBytes, 0, body, offset, mimeBytes.length);
        offset += mimeBytes.length;
        body[offset++] = 0;
        body[offset++] = 3;
        body[offset++] = 0;
        System.arraycopy(cover, 0, body, offset, cover.length);
        return body;
    }

    private static byte[] frame(String id, byte[] body, int version) {
        var result = new byte[10 + body.length];
        var idBytes = id.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(idBytes, 0, result, 0, 4);
        var size = version == 4 ? syncSafe(body.length) : integer(body.length);
        System.arraycopy(size, 0, result, 4, 4);
        System.arraycopy(body, 0, result, 10, body.length);
        return result;
    }

    private static int readInt(byte[] data, int offset) {
        return ((data[offset] & 0xff) << 24)
                | ((data[offset + 1] & 0xff) << 16)
                | ((data[offset + 2] & 0xff) << 8)
                | (data[offset + 3] & 0xff);
    }

    private static int readSyncSafe(byte[] data, int offset) {
        return ((data[offset] & 0x7f) << 21)
                | ((data[offset + 1] & 0x7f) << 14)
                | ((data[offset + 2] & 0x7f) << 7)
                | (data[offset + 3] & 0x7f);
    }

    private static byte[] integer(int value) {
        return new byte[]{
                (byte) (value >>> 24),
                (byte) (value >>> 16),
                (byte) (value >>> 8),
                (byte) value
        };
    }

    private static byte[] syncSafe(int value) {
        return new byte[]{
                (byte) ((value >>> 21) & 0x7f),
                (byte) ((value >>> 14) & 0x7f),
                (byte) ((value >>> 7) & 0x7f),
                (byte) (value & 0x7f)
        };
    }
}
