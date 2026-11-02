package com.ncmbreaker.app;

import java.util.Locale;

public final class FileSize {
    private FileSize() { }

    public static String format(long bytes) {
        if (bytes < 0) return "未知";
        if (bytes >= 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
        if (bytes >= 1024 * 1024) return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
        if (bytes >= 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        return bytes + " B";
    }
}
