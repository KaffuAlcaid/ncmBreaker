package com.ncmbreaker.download;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.CancellationException;

public final class DownloadCancellation {
    private volatile boolean cancelled;
    private Closeable connection;

    public synchronized void bind(Closeable connection) throws IOException {
        if (cancelled) {
            connection.close();
            throw new CancellationException();
        }
        this.connection = connection;
    }

    public synchronized void cancel() {
        cancelled = true;
        if (connection != null) {
            try { connection.close(); } catch (IOException ignored) { }
        }
    }

    public void check() {
        if (cancelled || Thread.currentThread().isInterrupted()) throw new CancellationException();
    }

    public boolean isCancelled() { return cancelled; }
}
