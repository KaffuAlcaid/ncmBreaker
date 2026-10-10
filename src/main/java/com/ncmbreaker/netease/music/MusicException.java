package com.ncmbreaker.netease.music;

import java.io.IOException;

public final class MusicException extends IOException {
    public MusicException(String message) {
        super(message);
    }

    public static String describe(Throwable error) {
        if (error instanceof MusicException) {
            return error.getMessage();
        }
        if (error instanceof java.net.http.HttpTimeoutException) {
            return "连接超时，请重试。";
        }
        return "操作失败，请检查网络及保存目录后重试。";
    }
}
