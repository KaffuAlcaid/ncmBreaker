package com.ncmbreaker.download;

import com.google.gson.*;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class DownloadStore implements AutoCloseable {
    static final Gson JSON = new GsonBuilder().registerTypeHierarchyAdapter(Path.class, new PathAdapter()).create();
    private static final class PathAdapter implements JsonSerializer<Path>, JsonDeserializer<Path> {
        @Override public JsonElement serialize(Path value, java.lang.reflect.Type type, JsonSerializationContext context) {
            return new JsonPrimitive(value.toString());
        }
        @Override public Path deserialize(JsonElement value, java.lang.reflect.Type type, JsonDeserializationContext context) {
            return Path.of(value.getAsString());
        }
    }

    record Change(String key, JsonElement value) {
        static Change put(String key, Object value) { return new Change(key, JSON.toJsonTree(value)); }
        static Change remove(String key) { return new Change(key, JsonNull.INSTANCE); }
    }

    private final Path directory;
    private final Map<String, JsonElement> records = new LinkedHashMap<>();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> new Thread(r, "download-records"));
    private FileChannel lockChannel;
    private FileLock lock;
    private FileChannel journal;
    private long sequence;
    private int changes;
    private volatile IOException failure;
    private boolean closed;

    DownloadStore(Path directory) throws IOException {
        this.directory = directory;
        try {
            Files.createDirectories(directory);
            lockChannel = FileChannel.open(directory.resolve("queue.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            lock = lockChannel.tryLock();
            if (lock == null) throw new IOException("下载记录正由另一个程序窗口使用");
            load();
            journal = FileChannel.open(directory.resolve("events.jsonl"), StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException exception) {
            writer.shutdown();
            release();
            throw new IOException("无法打开下载记录：" + exception.getMessage(), exception);
        }
    }

    Map<String, JsonElement> loaded() { return Map.copyOf(records); }

    synchronized CompletableFuture<Void> append(java.util.List<Change> updates) {
        if (closed) return CompletableFuture.failedFuture(new IOException("下载记录已关闭"));
        return CompletableFuture.runAsync(() -> {
            try {
                if (failure != null) throw failure;
                var event = new JsonObject();
                event.addProperty("version", 1);
                event.addProperty("sequence", ++sequence);
                event.add("changes", JSON.toJsonTree(updates));
                write(journal, JSON.toJson(event) + "\n");
                journal.force(false);
                for (var update : updates) apply(update);
                if (++changes >= 200 || journal.size() > 4 * 1024 * 1024) snapshot();
            } catch (IOException exception) {
                failure = exception;
                throw new java.util.concurrent.CompletionException(exception);
            }
        }, writer);
    }

    private void apply(Change change) {
        if (change.value() == null || change.value().isJsonNull()) records.remove(change.key());
        else records.put(change.key(), change.value());
    }

    private void load() throws IOException {
        var snapshot = directory.resolve("snapshot.json");
        if (Files.exists(snapshot)) {
            try (var input = Files.newBufferedReader(snapshot, StandardCharsets.UTF_8)) {
                var root = JsonParser.parseReader(input).getAsJsonObject();
                requireVersion(root);
                sequence = root.get("sequence").getAsLong();
                root.getAsJsonObject("records").entrySet().forEach(entry -> records.put(entry.getKey(), entry.getValue()));
            } catch (RuntimeException exception) { throw new IOException("下载快照无法读取", exception); }
        }
        var path = directory.resolve("events.jsonl");
        if (!Files.exists(path)) return;
        try (var file = new RandomAccessFile(path.toFile(), "rw")) {
            long validEnd = 0;
            String line;
            while ((line = file.readLine()) != null) {
                long end = file.getFilePointer();
                file.seek(end - 1);
                boolean terminated = file.read() == '\n';
                file.seek(end);
                if (!terminated) { file.setLength(validEnd); break; }
                try {
                    var event = JsonParser.parseString(new String(line.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8)).getAsJsonObject();
                    requireVersion(event);
                    long next = event.get("sequence").getAsLong();
                    if (next > sequence) {
                        if (next != sequence + 1) throw new IOException("下载记录顺序不完整");
                        for (var value : event.getAsJsonArray("changes")) apply(JSON.fromJson(value, Change.class));
                        sequence = next;
                    }
                } catch (RuntimeException exception) { throw new IOException("下载记录无法读取", exception); }
                validEnd = end;
            }
        }
    }

    private static void requireVersion(JsonObject root) throws IOException {
        if (!root.has("version") || root.get("version").getAsInt() != 1) throw new IOException("不支持此版本的下载记录");
    }

    private void snapshot() throws IOException {
        var root = new JsonObject();
        root.addProperty("version", 1);
        root.addProperty("sequence", sequence);
        root.add("records", JSON.toJsonTree(records));
        var temporary = directory.resolve("snapshot.tmp");
        try (var output = FileChannel.open(temporary, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            write(output, JSON.toJson(root) + "\n");
            output.force(true);
        }
        try { Files.move(temporary, directory.resolve("snapshot.json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, directory.resolve("snapshot.json"), StandardCopyOption.REPLACE_EXISTING);
        }
        journal.truncate(0);
        journal.force(false);
        changes = 0;
    }

    private static void write(FileChannel channel, String text) throws IOException {
        var buffer = ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
        while (buffer.hasRemaining()) channel.write(buffer);
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        writer.execute(() -> {
            try { if (failure == null) snapshot(); }
            catch (IOException exception) { failure = exception; }
            finally { release(); }
        });
        writer.shutdown();
    }

    private void release() {
        try { if (journal != null) journal.close(); } catch (IOException ignored) { }
        try { if (lock != null) lock.release(); } catch (IOException ignored) { }
        try { if (lockChannel != null) lockChannel.close(); } catch (IOException ignored) { }
    }
}
