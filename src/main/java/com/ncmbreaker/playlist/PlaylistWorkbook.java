package com.ncmbreaker.playlist;

import com.ncmbreaker.netease.music.MusicException;
import com.ncmbreaker.netease.music.MusicModels.*;
import org.dhatim.fastexcel.HyperLink;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;
import org.dhatim.fastexcel.reader.CellType;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;

public final class PlaylistWorkbook {
    private static final String[] HEADERS = {
            "序号", "歌曲", "歌手", "专辑", "时长", "歌曲 ID", "歌曲链接", "专辑 ID", "曲目序号", "详情状态", "封面链接"
    };
    private static final double[] WIDTHS = {8, 42, 30, 42, 12, 22, 48, 22, 12, 16, 64};
    private static final BigDecimal MILLIS_PER_DAY = BigDecimal.valueOf(86_400_000);

    private PlaylistWorkbook() { }

    public static String fileName(PlaylistContent content) {
        var name = content.playlist().name().replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_")
                .strip().replaceAll("[. ]+$", "");
        if (name.isBlank()) name = "歌单";
        if (name.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])")) name = "_" + name;
        name = name.substring(0, name.offsetByCodePoints(0, Math.min(name.codePointCount(0, name.length()), 80)));
        return name + (content.playlist().id() > 0 ? "_" + content.playlist().id() : "") + ".xlsx";
    }

    public static void write(Path target, PlaylistContent content, boolean overwrite) throws IOException {
        requireXlsx(target);
        target = target.toAbsolutePath().normalize();
        checkCancelled();
        var temporary = Files.createTempFile(target.getParent(), ".ncm-breaker-playlist-", ".tmp");
        try {
            try (var output = Files.newOutputStream(temporary); var workbook = new Workbook(output, "NCM Breaker", "0.1")) {
                workbook.setGlobalDefaultFont("Microsoft YaHei", 11);
                var sheet = workbook.newWorksheet("歌曲");
                for (int column = 0; column < HEADERS.length; column++) {
                    sheet.value(0, column, HEADERS[column]);
                    sheet.width(column, WIDTHS[column]);
                }
                sheet.range(0, 0, 0, HEADERS.length - 1).style().bold()
                        .horizontalAlignment("center").verticalAlignment("center").set();
                sheet.rowHeight(0, 28);
                int row = 1;
                for (var song : content.songs()) {
                    checkCancelled();
                    sheet.value(row, 0, row);
                    text(sheet, row, 1, song.title());
                    text(sheet, row, 2, String.join("\n", song.artists()));
                    text(sheet, row, 3, song.album());
                    if (song.durationMillis() > 0) sheet.value(row, 4, song.durationMillis() / 86_400_000.0);
                    sheet.style(row, 4).format("[m]:ss").set();
                    sheet.value(row, 5, Long.toString(song.id()));
                    sheet.style(row, 5).format("@").horizontalAlignment("left").set();
                    var url = "https://music.163.com/song?id=" + song.id();
                    sheet.value(row, 6, url);
                    sheet.hyperlink(row, 6, new HyperLink(url, url));
                    sheet.style(row, 6).underlined().set();
                    if (song.albumId() > 0) sheet.value(row, 7, Long.toString(song.albumId()));
                    sheet.style(row, 7).format("@").horizontalAlignment("left").set();
                    if (song.trackNumber() > 0) sheet.value(row, 8, song.trackNumber());
                    sheet.value(row, 9, song.detailAvailable() ? "正常" : "详情暂不可用");
                    text(sheet, row, 10, song.coverUrl());
                    sheet.range(row, 0, row, HEADERS.length - 1).style().verticalAlignment("center").wrapText(true).set();
                    int lines = Math.max(Math.max(lines(song.title(), 42), lines(song.album(), 42)),
                            Math.max(lines(String.join("\n", song.artists()), 30), lines(song.coverUrl(), 64)));
                    sheet.rowHeight(row, Math.min(Worksheet.MAX_ROW_HEIGHT, Math.max(26, lines * 16 + 8)));
                    row++;
                }
                sheet.setAutoFilter(0, 0, Math.max(0, row - 1), HEADERS.length - 1);
                writeInfo(workbook.newWorksheet("歌单信息"), content);
            }
            checkCancelled();
            if (overwrite) Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            else Files.move(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void writeInfo(Worksheet sheet, PlaylistContent content) {
        var playlist = content.playlist();
        sheet.width(0, 22); sheet.width(1, 80);
        String[] labels = {"歌单名称", "歌单 ID", "歌单链接", "歌单歌曲数", "导出歌曲数", "预期歌曲数", "分类", "封面链接"};
        String[] values = {playlist.name(), Long.toString(playlist.id()),
                playlist.id() > 0 ? "https://music.163.com/playlist?id=" + playlist.id() : "",
                Integer.toString(playlist.trackCount()), Integer.toString(content.songs().size()),
                Integer.toString(content.expectedCount()), playlist.category().toString(), playlist.coverUrl()};
        for (int row = 0; row < labels.length; row++) {
            sheet.value(row, 0, labels[row]);
            if (row >= 3 && row <= 5) sheet.value(row, 1, Integer.parseInt(values[row]));
            else text(sheet, row, 1, values[row]);
            if (row == 1) sheet.style(row, 1).format("@").horizontalAlignment("left").set();
            sheet.style(row, 0).bold().set();
            sheet.range(row, 0, row, 1).style().wrapText(true).verticalAlignment("center").set();
            sheet.rowHeight(row, Math.min(Worksheet.MAX_ROW_HEIGHT, Math.max(28, lines(values[row], 80) * 16 + 8)));
        }
    }

    private static void text(Worksheet sheet, int row, int column, String value) {
        if (!value.isEmpty()) sheet.value(row, column, value);
    }

    public static PlaylistContent read(Path path) throws IOException {
        requireXlsx(path);
        if (Files.size(path) > 32L * 1024 * 1024) throw new MusicException("歌单文件超过 32 MB。");
        try (var workbook = new ReadableWorkbook(path.toFile())) {
            var info = new LinkedHashMap<String, String>();
            var metadata = workbook.findSheet("歌单信息");
            if (metadata.isPresent()) {
                try (var rows = metadata.get().openStream()) {
                    var iterator = rows.iterator();
                    while (iterator.hasNext()) {
                        checkCancelled();
                        var row = iterator.next();
                        info.put(text(row, 0), text(row, 1));
                    }
                }
            }
            var sheet = workbook.findSheet("歌曲").orElseGet(workbook::getFirstSheet);
            var songs = new ArrayList<Song>();
            try (var rows = sheet.openStream()) {
                var iterator = rows.iterator();
                var columns = new LinkedHashMap<String, Integer>();
                while (iterator.hasNext()) {
                    checkCancelled();
                    var row = iterator.next();
                    if (row.getFirstNonEmptyCell().isEmpty()) continue;
                    if (columns.isEmpty()) {
                        for (int column = 0; column < row.getCellCount(); column++) {
                            var name = text(row, column).strip();
                            if (!name.isEmpty()) columns.put(name, column);
                        }
                        if (!columns.containsKey("歌曲 ID")) throw new MusicException("请选择包含“歌曲 ID”列的 XLSX 歌单文件。");
                        continue;
                    }
                    long id = number(value(row, columns, "歌曲 ID"), 0);
                    if (id <= 0) throw new MusicException("第 " + row.getRowNum() + " 行的歌曲 ID 无效。");
                    var title = value(row, columns, "歌曲");
                    var artists = value(row, columns, "歌手");
                    songs.add(new Song(id, title.isBlank() ? "歌曲 " + id : title,
                            artists.isBlank() ? List.of() : List.of(artists.split("\\R")), value(row, columns, "专辑"),
                            number(value(row, columns, "专辑 ID"), 0), value(row, columns, "封面链接"),
                            duration(row, columns.getOrDefault("时长", -1)),
                            Math.toIntExact(number(value(row, columns, "曲目序号"), 0)),
                            !"详情暂不可用".equals(value(row, columns, "详情状态"))));
                }
                if (columns.isEmpty()) throw new MusicException("歌单文件中没有表头。");
            }
            var name = info.getOrDefault("歌单名称", path.getFileName().toString().replaceFirst("(?i)\\.xlsx$", ""));
            long id = number(info.getOrDefault("歌单 ID", ""), 0);
            int count = Math.toIntExact(number(info.getOrDefault("歌单歌曲数", ""), songs.size()));
            int expected = Math.toIntExact(number(info.getOrDefault("预期歌曲数", ""), count));
            var category = Arrays.stream(Category.values()).filter(value -> value.toString().equals(info.get("分类")))
                    .findFirst().orElse(Category.CREATED);
            return new PlaylistContent(new Playlist(id, name, count, info.getOrDefault("封面链接", ""), category), songs, expected);
        } catch (MusicException | CancellationException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new MusicException("无法读取 XLSX 歌单，请检查文件和单元格内容。");
        }
    }

    private static String value(Row row, Map<String, Integer> columns, String name) throws MusicException {
        return text(row, columns.getOrDefault(name, -1));
    }

    private static String text(Row row, int column) throws MusicException {
        var cell = column < 0 ? null : row.getOptionalCell(column).orElse(null);
        if (cell == null) return "";
        if (cell.getType() == CellType.FORMULA || cell.getType() == CellType.ERROR) {
            throw new MusicException("第 " + row.getRowNum() + " 行包含公式或错误值，请将其转换为单元格值后导入。");
        }
        return cell.getText();
    }

    private static long number(String value, long fallback) throws MusicException {
        if (value.isBlank()) return fallback;
        try {
            long result = new BigDecimal(value.strip()).longValueExact();
            if (result < 0) throw new ArithmeticException();
            return result;
        } catch (NumberFormatException | ArithmeticException exception) {
            throw new MusicException("歌单中的 ID、数量或曲目序号必须是非负整数。");
        }
    }

    private static long duration(Row row, int column) throws MusicException {
        var value = text(row, column);
        if (value.isBlank()) return 0;
        try {
            var cell = row.getCell(column);
            var millis = cell.getType() == CellType.NUMBER
                    ? cell.asNumber().multiply(MILLIS_PER_DAY) : BigDecimal.ZERO;
            if (cell.getType() != CellType.NUMBER) {
                var parts = value.strip().split(":");
                if (parts.length < 2 || parts.length > 3) throw new NumberFormatException();
                for (var part : parts) millis = millis.multiply(BigDecimal.valueOf(60)).add(new BigDecimal(part));
                millis = millis.multiply(BigDecimal.valueOf(1000));
            }
            long result = millis.setScale(0, RoundingMode.HALF_UP).longValueExact();
            if (result < 0) throw new ArithmeticException();
            return result;
        } catch (NumberFormatException | ArithmeticException exception) {
            throw new MusicException("第 " + row.getRowNum() + " 行的时长无效。");
        }
    }

    private static int lines(String value, int width) {
        int result = 0;
        for (var line : value.split("\\R", -1)) {
            int length = line.codePoints().map(point -> point > 255 ? 2 : 1).sum();
            result += Math.max(1, (length + width - 1) / width);
        }
        return result;
    }

    private static void requireXlsx(Path path) throws MusicException {
        if (!path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            throw new MusicException("请选择 XLSX 歌单文件。");
        }
    }

    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException();
    }
}
