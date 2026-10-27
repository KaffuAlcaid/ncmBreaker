package com.ncmbreaker.audio;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

public record AudioResult(Path file, String format, long audioBytes, TrackMetadata metadata,
                          Outcome outcome, List<Path> additionalFiles, List<Warning> warnings) {
    public enum Outcome { WRITTEN, SKIPPED }
    public enum WarningCode { METADATA, COVER, LYRICS, TAGS, ADDITIONAL_FILE, PLAYLIST }
    public record Warning(WarningCode code, String message) { }
    public AudioResult { additionalFiles = List.copyOf(additionalFiles); warnings = List.copyOf(warnings); }
    public String warningText() {
        return warnings.stream().map(Warning::message).distinct().collect(Collectors.joining("；"));
    }
}
