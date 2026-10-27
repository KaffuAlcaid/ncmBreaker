package com.ncmbreaker.app;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ncmbreaker.NcmFileIO;
import com.ncmbreaker.netease.music.AudioQuality;
import com.ncmbreaker.netease.music.MusicModels.SearchType;
import com.ncmbreaker.audio.ProcessingOptions;
import com.ncmbreaker.audio.OutputPlanner;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.EnumMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class AppSettingsStore implements AutoCloseable {
    public enum Directory {
        NCM_INPUT, CONVERSION_OUTPUT, DOWNLOAD_OUTPUT, PLAYLIST_IMPORT, PLAYLIST_EXPORT
    }

    public enum Option {
        CONVERSION_TAGS(true), CONVERSION_COVER(true), DOWNLOAD_TAGS(true), DOWNLOAD_COVER(true),
        PLAYLIST_DIRECTORIES(false);
        private final boolean defaultValue;
        Option(boolean defaultValue) { this.defaultValue = defaultValue; }
    }

    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path directory;
    private final EnumMap<Directory, Path> directories = new EnumMap<>(Directory.class);
    private final EnumMap<Option, Boolean> options = new EnumMap<>(Option.class);
    private final ExecutorService writer = Executors.newSingleThreadExecutor(task -> new Thread(task, "settings-save"));
    private final AtomicBoolean saveWarningShown = new AtomicBoolean();
    private volatile Consumer<String> errorHandler = message -> { };
    private NcmFileIO.ConflictPolicy conflictPolicy = NcmFileIO.ConflictPolicy.RENAME;
    private AudioQuality batchQuality = AudioQuality.LOSSLESS;
    private ProcessingOptions downloadProcessing;
    private ProcessingOptions conversionProcessing;
    private String loadWarning;
    private boolean closed;
    public record SearchEntry(SearchType type, String query) { }
    private final java.util.List<SearchEntry> searchHistory = new java.util.ArrayList<>();

    public synchronized java.util.List<String> searchHistory(SearchType type) {
        return searchHistory.stream().filter(entry -> entry.type() == type).map(SearchEntry::query).toList();
    }

    public synchronized void rememberSearch(SearchType type, String query) {
        if (closed || query.isBlank() || query.length() > 160) return;
        var entry = new SearchEntry(type, query.strip());
        searchHistory.remove(entry); searchHistory.add(0, entry);
        while (searchHistory.size() > 30) searchHistory.remove(searchHistory.size() - 1);
        save();
    }

    public synchronized void clearSearchHistory(SearchType type) {
        if (!closed && searchHistory.removeIf(entry -> entry.type() == type)) save();
    }

    public AppSettingsStore() { this(ApplicationPaths.directory().resolve("config")); }

    AppSettingsStore(Path directory) {
        this.directory = directory;
        load();
    }

    public void setErrorHandler(Consumer<String> handler) {
        errorHandler = handler;
        if (loadWarning != null) {
            handler.accept(loadWarning);
            loadWarning = null;
        }
    }

    public synchronized Path directory(Directory key, Path fallback) { return directories.getOrDefault(key, fallback); }
    public synchronized boolean option(Option key) { return options.getOrDefault(key, key.defaultValue); }
    public synchronized NcmFileIO.ConflictPolicy conflictPolicy() { return conflictPolicy; }
    public synchronized AudioQuality batchQuality() { return batchQuality; }

    public synchronized ProcessingOptions processing(boolean conversion) {
        var value = conversion ? conversionProcessing : downloadProcessing;
        if (value != null) return new ProcessingOptions(option(conversion ? Option.CONVERSION_TAGS : Option.DOWNLOAD_TAGS),
                option(conversion ? Option.CONVERSION_COVER : Option.DOWNLOAD_COVER), value.enrich(), value.replaceExisting(),
                value.saveLyrics(), value.embedLyrics(), value.translation(), value.romanization(), value.exportCover(),
                value.albumCover(), value.playlist(), value.layout(), value.template(),
                conversion ? conflictPolicy : value.conflictPolicy());
        var defaults = ProcessingOptions.defaults();
        return new ProcessingOptions(option(conversion ? Option.CONVERSION_TAGS : Option.DOWNLOAD_TAGS),
                option(conversion ? Option.CONVERSION_COVER : Option.DOWNLOAD_COVER), false, false, false, false,
                false, false, false, false, false,
                !conversion && option(Option.PLAYLIST_DIRECTORIES) ? ProcessingOptions.Layout.PLAYLIST : ProcessingOptions.Layout.CURRENT,
                defaults.template(), conversion ? conflictPolicy : defaults.conflictPolicy());
    }

    public synchronized void setProcessing(boolean conversion, ProcessingOptions value) {
        if (closed) return;
        if (conversion) { conversionProcessing = value; conflictPolicy = value.conflictPolicy(); }
        else { downloadProcessing = value; options.put(Option.PLAYLIST_DIRECTORIES, value.layout() == ProcessingOptions.Layout.PLAYLIST); }
        options.put(conversion ? Option.CONVERSION_TAGS : Option.DOWNLOAD_TAGS, value.tags());
        options.put(conversion ? Option.CONVERSION_COVER : Option.DOWNLOAD_COVER, value.cover());
        save();
    }

    public synchronized void setDirectory(Directory key, Path value) {
        if (closed) return;
        var normalized = value.toAbsolutePath().normalize();
        if (!normalized.equals(directories.put(key, normalized))) save();
    }

    public synchronized void setOption(Option key, boolean value) {
        if (closed || option(key) == value) return;
        options.put(key, value);
        save();
    }

    public synchronized void setConflictPolicy(NcmFileIO.ConflictPolicy value) {
        if (closed || conflictPolicy == value) return;
        conflictPolicy = value;
        save();
    }

    public synchronized void setBatchPreferences(AudioQuality quality, boolean byPlaylist) {
        if (closed || batchQuality == quality && option(Option.PLAYLIST_DIRECTORIES) == byPlaylist) return;
        batchQuality = quality;
        options.put(Option.PLAYLIST_DIRECTORIES, byPlaylist);
        save();
    }

    public File chooserDirectory(Directory key, Path fallback) { return existingDirectory(directory(key, fallback)); }

    public static File existingDirectory(Path preferred) {
        for (var candidate = preferred; candidate != null; candidate = candidate.getParent()) {
            if (Files.isDirectory(candidate)) return candidate.toFile();
        }
        return null;
    }

    private void load() {
        var file = directory.resolve("settings.json");
        try {
            if (Files.notExists(file)) return;
            if (Files.size(file) > 64 * 1024) throw new IOException();
            JsonObject data;
            try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                data = JsonParser.parseReader(reader).getAsJsonObject();
            }
            if (!data.has("version") || !data.get("version").isJsonPrimitive()
                    || !data.getAsJsonPrimitive("version").isNumber() || !"1".equals(data.get("version").getAsString())) {
                throw new IOException();
            }
            for (var key : Directory.values()) {
                if (!data.has(key.name())) continue;
                try {
                    var value = data.get(key.name());
                    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException();
                    var path = Path.of(value.getAsString());
                    if (!path.isAbsolute()) throw new IllegalArgumentException();
                    directories.put(key, path.normalize());
                } catch (RuntimeException exception) { invalidField(); }
            }
            for (var key : Option.values()) {
                if (!data.has(key.name())) continue;
                var value = data.get(key.name());
                if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) options.put(key, value.getAsBoolean());
                else invalidField();
            }
            if (data.has("conflictPolicy")) {
                try { conflictPolicy = NcmFileIO.ConflictPolicy.valueOf(data.get("conflictPolicy").getAsString()); }
                catch (RuntimeException exception) { invalidField(); }
            }
            if (data.has("batchQuality")) {
                try { batchQuality = AudioQuality.valueOf(data.get("batchQuality").getAsString()); }
                catch (RuntimeException exception) { invalidField(); }
            }
            downloadProcessing = readProcessing(data, "downloadProcessing");
            conversionProcessing = readProcessing(data, "conversionProcessing");
            if (data.has("searchHistory")) {
                try {
                    for (var item : data.getAsJsonArray("searchHistory")) {
                        var entry = JSON.fromJson(item, SearchEntry.class);
                        if (entry != null && entry.type() != null && entry.query() != null
                                && !entry.query().isBlank() && entry.query().length() <= 160 && searchHistory.size() < 30) searchHistory.add(entry);
                    }
                } catch (RuntimeException exception) { invalidField(); }
            }
        } catch (IOException | RuntimeException exception) {
            loadWarning = "设置文件无法读取，已使用默认设置";
        }
    }

    private void invalidField() { loadWarning = "部分设置无效，已使用对应的默认设置"; }

    private ProcessingOptions readProcessing(JsonObject data, String key) {
        if (!data.has(key)) return null;
        try {
            var value = JSON.fromJson(data.get(key), ProcessingOptions.class);
            if (value == null) throw new IllegalArgumentException();
            OutputPlanner.validate(value);
            return value;
        } catch (RuntimeException exception) { invalidField(); return null; }
    }

    private void save() {
        var data = new JsonObject();
        data.addProperty("version", 1);
        directories.forEach((key, value) -> data.addProperty(key.name(), value.toString()));
        options.forEach((key, value) -> data.addProperty(key.name(), value));
        data.addProperty("conflictPolicy", conflictPolicy.name());
        data.addProperty("batchQuality", batchQuality.name());
        data.add("searchHistory", JSON.toJsonTree(searchHistory));
        if (downloadProcessing != null) data.add("downloadProcessing", JSON.toJsonTree(downloadProcessing));
        if (conversionProcessing != null) data.add("conversionProcessing", JSON.toJsonTree(conversionProcessing));
        var snapshot = JSON.toJson(data) + "\n";
        writer.execute(() -> {
            try {
                Files.createDirectories(directory);
                var temporary = Files.createTempFile(directory, "settings-", ".tmp");
                try {
                    Files.writeString(temporary, snapshot, StandardCharsets.UTF_8);
                    try {
                        Files.move(temporary, directory.resolve("settings.json"),
                                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    } catch (AtomicMoveNotSupportedException exception) {
                        Files.move(temporary, directory.resolve("settings.json"), StandardCopyOption.REPLACE_EXISTING);
                    }
                } finally { Files.deleteIfExists(temporary); }
            } catch (IOException | RuntimeException exception) {
                if (saveWarningShown.compareAndSet(false, true)) {
                    errorHandler.accept("设置无法保存，本次修改仅在当前运行期间有效");
                }
            }
        });
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        writer.shutdown();
    }
}
