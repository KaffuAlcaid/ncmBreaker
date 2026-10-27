package com.ncmbreaker.netease.music;

import java.io.IOException;

public final class MusicException extends IOException {
    public enum Kind { NETWORK, AUTHORIZATION, QUALITY, FORMAT, FILESYSTEM, UNKNOWN }
    private final Kind kind;
    public MusicException(String message) {
        this(Kind.UNKNOWN, message);
    }
    public MusicException(Kind kind, String message) { super(message); this.kind = kind; }
    public static Kind kind(Throwable error) {
        while ((error instanceof java.util.concurrent.ExecutionException || error instanceof java.util.concurrent.CompletionException)
                && error.getCause() != null) error = error.getCause();
        if (error instanceof MusicException music) return music.kind;
        if (error instanceof com.ncmbreaker.netease.http.NeteaseClient.NetworkException) return Kind.NETWORK;
        if (error instanceof java.net.SocketException || error instanceof java.net.UnknownHostException
                || error instanceof java.net.http.HttpTimeoutException || error instanceof java.util.concurrent.TimeoutException
                || error instanceof javax.net.ssl.SSLException) return Kind.NETWORK;
        if (error instanceof java.nio.file.FileSystemException || error instanceof SecurityException) return Kind.FILESYSTEM;
        return Kind.UNKNOWN;
    }

    public static String describe(Throwable error) {
        while ((error instanceof java.util.concurrent.ExecutionException
                || error instanceof java.util.concurrent.CompletionException) && error.getCause() != null) {
            error = error.getCause();
        }
        if (error instanceof MusicException) {
            return error.getMessage();
        }
        if (error instanceof com.ncmbreaker.netease.http.NeteaseClient.NetworkException && error.getCause() != null) {
            return describe(error.getCause());
        }
        if (error instanceof java.net.http.HttpTimeoutException || error instanceof java.net.SocketTimeoutException
                || error instanceof java.util.concurrent.TimeoutException) {
            return "连接超时，请重试";
        }
        if (error instanceof java.net.UnknownHostException) return "无法解析服务器地址，请检查网络或代理设置";
        if (error instanceof java.net.ConnectException) return "无法连接服务器，请检查网络或代理设置";
        if (error instanceof java.net.SocketException) return "网络连接已中断，请重试";
        if (error instanceof javax.net.ssl.SSLException) return "安全连接失败，请检查系统时间或网络设置";
        if (error instanceof java.nio.file.AccessDeniedException || error instanceof SecurityException) {
            return "无法写入保存目录，请检查目录权限或文件占用情况";
        }
        if (error instanceof java.nio.file.NoSuchFileException) return "文件或保存目录不存在，请重新选择目录";
        var message = error.getMessage();
        if (error instanceof java.nio.file.FileSystemException && message != null) {
            var lower = message.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("no space") || lower.contains("not enough space") || lower.contains("disk full")
                    || lower.contains("磁盘空间不足")) return "磁盘空间不足，请清理空间或更换保存目录";
        }
        return message == null || message.isBlank() ? error.toString() : message;
    }
}
