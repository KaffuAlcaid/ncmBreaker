package com.ncmbreaker;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class NcmDecoder {
    private static final byte[] CORE_KEY = "hzHRAmso5kInbaxW".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] METADATA_KEY = "#14ljk_!\\]&0U<'(".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] KEY_PREFIX = "neteasecloudmusic".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] METADATA_PREFIX = "163 key(Don't modify):".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] JSON_PREFIX = "music:".getBytes(StandardCharsets.US_ASCII);

    public record Metadata(
            String title,
            List<String> artists,
            String album,
            String declaredFormat,
            String rawJson
    ) {
        public Metadata {
            title = Objects.requireNonNullElse(title, "");
            artists = List.copyOf(artists == null ? List.of() : artists);
            album = Objects.requireNonNullElse(album, "");
            declaredFormat = Objects.requireNonNullElse(declaredFormat, "");
            rawJson = Objects.requireNonNullElse(rawJson, "");
        }

        public String artistDisplay() {
            return String.join(", ", artists);
        }
    }

    byte[] decodeStreamKey(byte[] encryptedKey) throws DecodingException {
        var ciphertext = encryptedKey.clone();
        xor(ciphertext, 0x64);
        var plaintext = decryptAes(ciphertext, CORE_KEY, "密钥区");
        if (!startsWith(plaintext, KEY_PREFIX) || plaintext.length == KEY_PREFIX.length) {
            throw new DecodingException("密钥区明文前缀不匹配");
        }
        return java.util.Arrays.copyOfRange(plaintext, KEY_PREFIX.length, plaintext.length);
    }

    Metadata decodeMetadata(byte[] encryptedMetadata) throws DecodingException {
        var encoded = encryptedMetadata.clone();
        xor(encoded, 0x63);
        if (!startsWith(encoded, METADATA_PREFIX)) {
            throw new DecodingException("元数据区前缀不匹配");
        }

        byte[] ciphertext;
        try {
            ciphertext = Base64.getDecoder().decode(
                    java.util.Arrays.copyOfRange(encoded, METADATA_PREFIX.length, encoded.length)
            );
        } catch (IllegalArgumentException exception) {
            throw new DecodingException("元数据区不是有效的 Base64", exception);
        }

        var plaintext = decryptAes(ciphertext, METADATA_KEY, "元数据区");
        if (!startsWith(plaintext, JSON_PREFIX)) {
            throw new DecodingException("元数据明文前缀不匹配");
        }
        var rawJson = new String(
                plaintext,
                JSON_PREFIX.length,
                plaintext.length - JSON_PREFIX.length,
                StandardCharsets.UTF_8
        );
        return parseMetadata(rawJson);
    }

    AudioCipher newAudioCipher(byte[] streamKey) {
        return new AudioCipher(streamKey);
    }

    String detectAudioFormat(byte[] header) {
        if (header.length >= 3 && header[0] == 'I' && header[1] == 'D' && header[2] == '3') {
            return "mp3";
        }
        if (header.length >= 4 && header[0] == 'f' && header[1] == 'L' && header[2] == 'a' && header[3] == 'C') {
            return "flac";
        }
        if (header.length >= 2 && (header[0] & 0xff) == 0xff && (header[1] & 0xe0) == 0xe0) {
            return "mp3";
        }
        return "unknown";
    }

    String detectCoverFormat(byte[] header) {
        if (header.length == 0) {
            return "none";
        }
        if (header.length >= 8
                && (header[0] & 0xff) == 0x89
                && header[1] == 'P'
                && header[2] == 'N'
                && header[3] == 'G') {
            return "png";
        }
        if (header.length >= 3
                && (header[0] & 0xff) == 0xff
                && (header[1] & 0xff) == 0xd8
                && (header[2] & 0xff) == 0xff) {
            return "jpeg";
        }
        return "unknown";
    }

    private static byte[] decryptAes(byte[] ciphertext, byte[] key, String label)
            throws DecodingException {
        try {
            var cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"));
            return cipher.doFinal(ciphertext);
        } catch (BadPaddingException | IllegalBlockSizeException exception) {
            throw new DecodingException(label + "无法使用经典 AES 密钥解开", exception);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("当前 JDK 不支持所需 AES 算法", exception);
        }
    }

    private static void xor(byte[] data, int value) {
        for (var index = 0; index < data.length; index++) {
            data[index] ^= (byte) value;
        }
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (var index = 0; index < prefix.length; index++) {
            if (data[index] != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    private static Metadata parseMetadata(String rawJson) throws DecodingException {
        try {
            var parsed = new JsonParser(rawJson).parse();
            if (!(parsed instanceof Map<?, ?> root)) {
                throw new IllegalArgumentException("根节点不是对象");
            }
            var title = stringValue(root.get("musicName"));
            var album = stringValue(root.get("album"));
            var format = stringValue(root.get("format")).toLowerCase(Locale.ROOT);
            var artists = artistNames(root.get("artist"));
            return new Metadata(title, artists, album, format, rawJson);
        } catch (RuntimeException exception) {
            throw new DecodingException("元数据 JSON 结构无法识别", exception);
        }
    }

    private static String stringValue(Object value) {
        if (value == null) {
            return "";
        }
        return value instanceof String text ? text : value.toString();
    }

    private static List<String> artistNames(Object value) {
        if (!(value instanceof List<?> entries)) {
            return List.of();
        }
        var names = new ArrayList<String>();
        for (var entry : entries) {
            var name = entry instanceof List<?> fields && !fields.isEmpty()
                    ? stringValue(fields.get(0))
                    : entry instanceof String text ? text : "";
            if (!name.isBlank()) {
                names.add(name);
            }
        }
        return List.copyOf(names);
    }

    static final class AudioCipher {
        private final byte[] keyStream = new byte[256];

        private AudioCipher(byte[] key) {
            if (key.length == 0) {
                throw new IllegalArgumentException("音频流密钥不能为空");
            }
            var box = new int[256];
            for (var index = 0; index < box.length; index++) {
                box[index] = index;
            }
            for (int index = 0, swapIndex = 0; index < box.length; index++) {
                swapIndex = (swapIndex + box[index] + (key[index % key.length] & 0xff)) & 0xff;
                var value = box[index];
                box[index] = box[swapIndex];
                box[swapIndex] = value;
            }
            for (var offset = 0; offset < keyStream.length; offset++) {
                var index = (offset + 1) & 0xff;
                var swapIndex = (box[index] + index) & 0xff;
                keyStream[offset] = (byte) box[(box[index] + box[swapIndex]) & 0xff];
            }
        }

        void apply(byte[] data, int length, long absoluteOffset) {
            apply(data, 0, length, absoluteOffset);
        }

        void apply(byte[] data, int dataOffset, int length, long absoluteOffset) {
            for (var offset = 0; offset < length; offset++) {
                data[dataOffset + offset] ^= keyStream[(int) ((absoluteOffset + offset) & 0xff)];
            }
        }
    }

    static final class DecodingException extends Exception {
        private DecodingException(String message) {
            super(message);
        }

        private DecodingException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final class JsonParser {
        private final String text;
        private int index;

        private JsonParser(String text) {
            this.text = Objects.requireNonNull(text, "text");
        }

        private Object parse() {
            skipWhitespace();
            var value = readValue();
            skipWhitespace();
            if (index != text.length()) {
                throw error("JSON 尾部存在额外内容");
            }
            return value;
        }

        private Object readValue() {
            skipWhitespace();
            if (index >= text.length()) {
                throw error("JSON 提前结束");
            }
            return switch (text.charAt(index)) {
                case '{' -> readObject();
                case '[' -> readArray();
                case '"' -> readString();
                case 't' -> readLiteral("true", Boolean.TRUE);
                case 'f' -> readLiteral("false", Boolean.FALSE);
                case 'n' -> readLiteral("null", null);
                default -> readNumber();
            };
        }

        private Map<String, Object> readObject() {
            expect('{');
            var result = new LinkedHashMap<String, Object>();
            skipWhitespace();
            if (consume('}')) {
                return result;
            }
            while (true) {
                skipWhitespace();
                if (index >= text.length() || text.charAt(index) != '"') {
                    throw error("对象键必须是字符串");
                }
                var key = readString();
                skipWhitespace();
                expect(':');
                result.put(key, readValue());
                skipWhitespace();
                if (consume('}')) {
                    return result;
                }
                expect(',');
            }
        }

        private List<Object> readArray() {
            expect('[');
            var result = new ArrayList<>();
            skipWhitespace();
            if (consume(']')) {
                return result;
            }
            while (true) {
                result.add(readValue());
                skipWhitespace();
                if (consume(']')) {
                    return result;
                }
                expect(',');
            }
        }

        private String readString() {
            expect('"');
            var result = new StringBuilder();
            while (index < text.length()) {
                var character = text.charAt(index++);
                if (character == '"') {
                    return result.toString();
                }
                if (character == '\\') {
                    if (index >= text.length()) {
                        throw error("字符串转义不完整");
                    }
                    var escaped = text.charAt(index++);
                    switch (escaped) {
                        case '"', '\\', '/' -> result.append(escaped);
                        case 'b' -> result.append('\b');
                        case 'f' -> result.append('\f');
                        case 'n' -> result.append('\n');
                        case 'r' -> result.append('\r');
                        case 't' -> result.append('\t');
                        case 'u' -> result.append(readUnicodeEscape());
                        default -> throw error("未知字符串转义: \\" + escaped);
                    }
                } else {
                    if (character < 0x20) {
                        throw error("字符串包含控制字符");
                    }
                    result.append(character);
                }
            }
            throw error("字符串没有结束引号");
        }

        private char readUnicodeEscape() {
            if (index + 4 > text.length()) {
                throw error("Unicode 转义不完整");
            }
            var value = 0;
            for (var count = 0; count < 4; count++) {
                var digit = Character.digit(text.charAt(index++), 16);
                if (digit < 0) {
                    throw error("Unicode 转义包含非十六进制字符");
                }
                value = (value << 4) | digit;
            }
            return (char) value;
        }

        private Object readLiteral(String literal, Object value) {
            if (!text.startsWith(literal, index)) {
                throw error("无效 JSON 值");
            }
            index += literal.length();
            return value;
        }

        private BigDecimal readNumber() {
            var start = index;
            consume('-');
            readDigits();
            if (consume('.')) {
                readDigits();
            }
            if (consume('e') || consume('E')) {
                if (!consume('+')) {
                    consume('-');
                }
                readDigits();
            }
            try {
                return new BigDecimal(text.substring(start, index));
            } catch (NumberFormatException exception) {
                throw error("无效数字");
            }
        }

        private void readDigits() {
            var start = index;
            while (index < text.length() && Character.isDigit(text.charAt(index))) {
                index++;
            }
            if (start == index) {
                throw error("数字缺少数位");
            }
        }

        private boolean consume(char expected) {
            if (index < text.length() && text.charAt(index) == expected) {
                index++;
                return true;
            }
            return false;
        }

        private void expect(char expected) {
            if (!consume(expected)) {
                throw error("预期字符 '" + expected + "'");
            }
        }

        private void skipWhitespace() {
            while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
                index++;
            }
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + "，位置 " + index);
        }
    }
}
