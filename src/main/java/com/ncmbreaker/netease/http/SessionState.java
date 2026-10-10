package com.ncmbreaker.netease.http;

import java.util.List;

public record SessionState(int version, String deviceId, List<Cookie> cookies) {
    public SessionState { cookies = List.copyOf(cookies); }

    public record Cookie(String origin, String name, String value, String domain, String path,
                         long expiresAt, boolean secure, boolean httpOnly, int version, String ports) {
        @Override public String toString() { return "Cookie[redacted]"; }
    }

    @Override public String toString() { return "SessionState[redacted]"; }
}
