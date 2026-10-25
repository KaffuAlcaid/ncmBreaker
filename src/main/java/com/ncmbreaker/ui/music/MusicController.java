package com.ncmbreaker.ui.music;

import com.ncmbreaker.netease.auth.LoginSession;
import com.ncmbreaker.netease.music.MusicException;
import com.ncmbreaker.netease.music.MusicModels.*;
import com.ncmbreaker.netease.music.MusicService;
import com.ncmbreaker.playlist.MusicLink;
import com.ncmbreaker.playlist.PlaylistWorkbook;
import javax.swing.SwingWorker;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

final class MusicController implements AutoCloseable {
    private final MusicPanel view;
    private LoginSession session;
    private SwingWorker<?, ?> libraryWorker;
    private SwingWorker<?, ?> songsWorker;
    private SwingWorker<?, ?> exportWorker;
    private long sessionGeneration;
    private long songsGeneration;
    private boolean closed;

    MusicController(MusicPanel view) { this.view = view; }
    LoginSession session() { return session; }
    boolean isBusy() { return libraryWorker != null || songsWorker != null || exportWorker != null; }

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
        long navigation = songsGeneration;
        view.libraryLoading(true);
        libraryWorker = new SwingWorker<List<Playlist>, Void>() {
            @Override protected List<Playlist> doInBackground() throws Exception { return new MusicService(current).userPlaylists(); }
            @Override protected void done() {
                if (closed || generation != sessionGeneration || libraryWorker != this) return;
                libraryWorker = null; view.libraryLoading(false);
                try { view.showPlaylists(get(), navigation == songsGeneration); }
                catch (CancellationException ignored) { }
                catch (Exception exception) { view.showLibraryError(message(exception)); }
            }
        };
        libraryWorker.execute();
    }

    void loadPlaylist(Playlist playlist) {
        if (session == null || closed) return;
        var current = session;
        load(() -> new MusicService(current).playlist(playlist.id(), count -> { }));
    }

    void search(String keywords, SearchType type, int offset) {
        if (closed) return;
        if (session == null) { view.showStatus("请先登录网易云账号"); return; }
        if (keywords.isBlank()) { view.showStatus("请输入搜索关键词"); return; }
        var current = session;
        load(() -> new MusicService(current).search(keywords, type, offset), view::showSearchResults, "正在搜索");
    }

    void openSearchCollection(long id, SearchType type) {
        if (closed || session == null) return;
        var current = session;
        var previous = view.captureView();
        load(() -> type == SearchType.ALBUM ? new MusicService(current).album(id)
                        : new MusicService(current).playlist(id, count -> { }),
                content -> view.showSearchCollection(content, previous), "正在读取歌曲");
    }

    void open(String value, SearchType defaultType) {
        if (session == null) { view.showStatus("请先登录网易云账号"); return; }
        try {
            var link = MusicLink.parse(value, defaultType);
            view.selectLinkType(link.type());
            var current = session;
            if (link.song()) {
                load(() -> {
                    var songs = new MusicService(current).songs(List.of(link.id()), count -> { });
                    return new PlaylistContent(new Playlist(0, "单曲", 1, "", Category.CREATED), songs, 1);
                });
            } else if (link.type() == SearchType.ALBUM) load(() -> new MusicService(current).album(link.id()));
            else load(() -> new MusicService(current).playlist(link.id(), count -> { }));
        } catch (MusicException exception) { view.showStatus(exception.getMessage()); }
    }

    void importFile(Path path) { load(() -> PlaylistWorkbook.read(path)); }

    void exportFile(Path path, PlaylistContent content, boolean overwrite) {
        if (closed || exportWorker != null) return;
        view.exporting(true);
        exportWorker = new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception {
                PlaylistWorkbook.write(path, content, overwrite);
                return null;
            }
            @Override protected void done() {
                exportWorker = null;
                if (closed) return;
                view.exporting(false);
                try {
                    get();
                    view.showStatus("已导出 " + content.songs().size() + " 首歌曲：" + path);
                    if (!content.songs().isEmpty()) view.taskSucceeded();
                } catch (CancellationException ignored) { }
                catch (Exception exception) { view.showStatus("导出失败，请检查保存目录权限或文件占用情况"); }
            }
        };
        exportWorker.execute();
    }

    void clearSongs() {
        songsGeneration++;
        if (songsWorker != null) songsWorker.cancel(true);
        songsWorker = null;
        view.songsLoading(false);
    }

    private void load(Callable<PlaylistContent> operation) {
        load(operation, view::showContent, "正在读取歌曲");
    }

    private <T> void load(Callable<T> operation, Consumer<T> display, String message) {
        if (closed) return;
        if (songsWorker != null) songsWorker.cancel(true);
        long generation = ++songsGeneration;
        view.songsLoading(true);
        view.showStatus(message);
        songsWorker = new SwingWorker<T, Void>() {
            @Override protected T doInBackground() throws Exception { return operation.call(); }
            @Override protected void done() {
                if (closed || generation != songsGeneration) return;
                songsWorker = null; view.songsLoading(false);
                try { display.accept(get()); }
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
        if (exportWorker != null) exportWorker.cancel(true);
    }
}
