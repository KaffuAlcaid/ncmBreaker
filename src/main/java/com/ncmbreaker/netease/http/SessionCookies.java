package com.ncmbreaker.netease.http;

import java.net.CookieManager;
import java.net.CookieStore;
import java.net.HttpCookie;
import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class SessionCookies implements CookieStore {
    private final CookieStore delegate = new CookieManager().getCookieStore();
    private final Map<HttpCookie, SessionState.Cookie> saved = new LinkedHashMap<>();

    @Override public synchronized void add(URI uri, HttpCookie cookie) {
        delegate.add(uri, cookie);
        if (cookie.hasExpired() || uri == null) {
            saved.remove(cookie);
            return;
        }
        long expiresAt = -1;
        if (cookie.getMaxAge() >= 0) {
            long now = Instant.now().getEpochSecond();
            expiresAt = now + Math.min(cookie.getMaxAge(), Long.MAX_VALUE - now);
        }
        saved.put(cookie, new SessionState.Cookie(uri.toString(), cookie.getName(), cookie.getValue(),
                cookie.getDomain(), cookie.getPath(), expiresAt, cookie.getSecure(), cookie.isHttpOnly(),
                cookie.getVersion(), cookie.getPortlist()));
    }

    synchronized List<SessionState.Cookie> snapshot() {
        saved.keySet().retainAll(delegate.getCookies());
        return List.copyOf(saved.values());
    }

    synchronized void restore(List<SessionState.Cookie> entries) {
        removeAll();
        long now = Instant.now().getEpochSecond();
        for (var entry : entries) {
            var origin = URI.create(entry.origin());
            if (!"https".equalsIgnoreCase(origin.getScheme()) || origin.getUserInfo() != null
                    || !("music.163.com".equalsIgnoreCase(origin.getHost())
                    || "interfacepc.music.163.com".equalsIgnoreCase(origin.getHost()))) {
                throw new IllegalArgumentException("Invalid cookie origin.");
            }
            if (entry.expiresAt() >= 0 && entry.expiresAt() <= now) continue;
            var cookie = new HttpCookie(entry.name(), entry.value());
            cookie.setDomain(entry.domain());
            cookie.setPath(entry.path());
            cookie.setSecure(entry.secure());
            cookie.setHttpOnly(entry.httpOnly());
            cookie.setVersion(entry.version());
            cookie.setPortlist(entry.ports());
            cookie.setMaxAge(entry.expiresAt() < 0 ? -1 : entry.expiresAt() - now);
            delegate.add(origin, cookie);
            saved.put(cookie, entry);
        }
    }

    @Override public synchronized List<HttpCookie> get(URI uri) { return delegate.get(uri); }
    @Override public synchronized List<HttpCookie> getCookies() { return delegate.getCookies(); }
    @Override public synchronized List<URI> getURIs() { return delegate.getURIs(); }
    @Override public synchronized boolean remove(URI uri, HttpCookie cookie) {
        saved.remove(cookie);
        return delegate.remove(uri, cookie);
    }
    @Override public synchronized boolean removeAll() {
        saved.clear();
        return delegate.removeAll();
    }
}
