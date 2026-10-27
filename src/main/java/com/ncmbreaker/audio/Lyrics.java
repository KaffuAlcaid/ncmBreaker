package com.ncmbreaker.audio;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

public record Lyrics(State state, String original, String translated, String romanized) {
    public enum State { AVAILABLE, INSTRUMENTAL, UNAVAILABLE }
    private static final Pattern TIME = Pattern.compile("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]");
    private static final Pattern OFFSET = Pattern.compile("(?im)^\\[offset:([+-]?\\d+)]\\s*$");
    public Lyrics {
        original = Objects.requireNonNullElse(original, "");
        translated = Objects.requireNonNullElse(translated, "");
        romanized = Objects.requireNonNullElse(romanized, "");
    }
    public static Lyrics unavailable() { return new Lyrics(State.UNAVAILABLE, "", "", ""); }

    public String content(boolean translation, boolean romanization) {
        if (state != State.AVAILABLE) return "";
        var timeline = new TreeMap<Long, LinkedHashSet<String>>();
        var untimed = new ArrayList<String>();
        add(original, timeline, untimed);
        if (translation) add(translated, timeline, untimed);
        if (romanization) add(romanized, timeline, untimed);
        var result = new StringBuilder();
        for (var line : new LinkedHashSet<>(untimed)) result.append(line).append('\n');
        timeline.forEach((time, lines) -> {
            var timestamp = "[%02d:%02d.%03d]".formatted(time / 60000, time / 1000 % 60, time % 1000);
            for (var line : lines) result.append(timestamp).append(line).append('\n');
        });
        return result.toString();
    }

    private static void add(String source, TreeMap<Long, LinkedHashSet<String>> timeline, List<String> untimed) {
        long offset = 0;
        var offsetMatcher = OFFSET.matcher(source);
        if (offsetMatcher.find()) {
            try { offset = Long.parseLong(offsetMatcher.group(1)); } catch (NumberFormatException ignored) { }
        }
        for (var line : source.split("\\R")) {
            if (line.isBlank() || OFFSET.matcher(line).matches()) continue;
            if (line.stripLeading().startsWith("{")) {
                try {
                    var metadata = com.google.gson.JsonParser.parseString(line).getAsJsonObject();
                    if (metadata.has("t") && metadata.has("c") && metadata.get("c").isJsonArray()) {
                        var credit = new StringBuilder();
                        for (var part : metadata.getAsJsonArray("c")) {
                            if (part.isJsonObject()) credit.append(TrackMetadata.text(part.getAsJsonObject(), "tx"));
                        }
                        if (!credit.isEmpty()) timeline.computeIfAbsent(Math.max(0, metadata.get("t").getAsLong() - offset),
                                ignored -> new LinkedHashSet<>()).add(credit.toString());
                        continue;
                    }
                } catch (RuntimeException ignored) { }
            }
            var matcher = TIME.matcher(line);
            var times = new ArrayList<Long>();
            int end = 0;
            while (matcher.find() && line.substring(end, matcher.start()).isBlank()) {
                int seconds = Integer.parseInt(matcher.group(2));
                if (seconds >= 60) break;
                var fraction = matcher.group(3);
                long millis = fraction == null ? 0 : Long.parseLong((fraction + "000").substring(0, 3));
                long time = Long.parseLong(matcher.group(1)) * 60000 + seconds * 1000L + millis;
                times.add(Math.max(0, time - offset));
                end = matcher.end();
            }
            if (times.isEmpty()) { untimed.add(line); continue; }
            var text = line.substring(end).strip();
            for (long time : times) timeline.computeIfAbsent(time, ignored -> new LinkedHashSet<>()).add(text);
        }
    }
}
