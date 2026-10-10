package com.ncmbreaker.ui.music;

import com.ncmbreaker.netease.auth.LoginSession;
import com.ncmbreaker.netease.music.MusicException;
import com.ncmbreaker.netease.music.MusicModels.*;
import com.ncmbreaker.netease.music.MusicService;
import com.ncmbreaker.playlist.MusicLink;
import com.ncmbreaker.playlist.PlaylistImport;
import javax.swing.SwingWorker;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

final class MusicController implements AutoCloseable {
    private final MusicPanel view;
    private LoginSession session;
    private SwingWorker<?, ?> libraryWorker;
    private SwingWorker<?, ?> songsWorker;
    private long sessionGeneration;
    private long songsGeneration;
    private boolean closed;

    MusicController(MusicPanel view) { this.view = view; }
    LoginSession session() { return session; }

    void setSession(LoginSession session) {
        sessionGeneration++; songsGeneration++;
        if (libraryWorker != null) libraryWorker.cancel(true);
        if (songsWorker != null) songsWorker.cancel(true);
        libraryWorker = null; songsWorker = null;
        this.session = session;
        view.resetAccount(session == null ? null : session.account());
        if (session != null) refresh();
    }

    void refresh() {
        if (closed || session == null) { view.showStatus("请先登录网易云账号"); return; }
        if (libraryWorker != null) libraryWorker.cancel(true);
        var current = session;
        long generation = sessionGeneration;
        view.libraryLoading(true);
        libraryWorker = new SwingWorker<List<Playlist>, Void>() {
            @Override protected List<Playlist> doInBackground() throws Exception { return new MusicService(current).userPlaylists(); }
            @Override protected void done() {
                if (closed || generation != sessionGeneration || libraryWorker != this) return;
                libraryWorker = null; view.libraryLoading(false);
                try { view.showPlaylists(get()); }
                catch (CancellationException ignored) { }
                catch (Exception exception) { view.showStatus(message(exception)); }
            }
        };
        libraryWorker.execute();
    }

    void loadPlaylist(Playlist playlist) {
        if (session == null || closed) return;
        var current = session;
        load(() -> new MusicService(current).playlist(playlist.id(), count -> { }));
    }

    void open(String value, boolean songId) {
        if (session == null) { view.showStatus("请先登录网易云账号"); return; }
        try {
            var link = MusicLink.parse(value, songId);
            view.selectLinkType(link.song());
            var current = session;
            if (link.song()) {
                load(() -> {
                    var songs = new MusicService(current).songs(List.of(link.id()), count -> { });
                    return new PlaylistContent(new Playlist(0, "单曲", 1, "", Category.CREATED), songs, 1);
                });
            } else load(() -> new MusicService(current).playlist(link.id(), count -> { }));
        } catch (MusicException exception) { view.showStatus(exception.getMessage()); }
    }

    void importFile(Path path) { load(() -> PlaylistImport.read(path)); }

    void clearSongs() {
        songsGeneration++;
        if (songsWorker != null) songsWorker.cancel(true);
        songsWorker = null;
        view.songsLoading(false);
    }

    private void load(Callable<PlaylistContent> operation) {
        if (closed) return;
        if (songsWorker != null) songsWorker.cancel(true);
        long generation = ++songsGeneration;
        view.songsLoading(true);
        songsWorker = new SwingWorker<PlaylistContent, Void>() {
            @Override protected PlaylistContent doInBackground() throws Exception { return operation.call(); }
            @Override protected void done() {
                if (closed || generation != songsGeneration) return;
                songsWorker = null; view.songsLoading(false);
                try { view.showContent(get()); }
                catch (CancellationException ignored) { }
                catch (Exception exception) { view.showStatus(message(exception)); }
            }
        };
        songsWorker.execute();
    }

    private static String message(Exception error) {
        return MusicException.describe(error instanceof ExecutionException ? error.getCause() : error);
    }

    @Override public void close() {
        closed = true; sessionGeneration++; songsGeneration++;
        if (libraryWorker != null) libraryWorker.cancel(true);
        if (songsWorker != null) songsWorker.cancel(true);
    }
}
