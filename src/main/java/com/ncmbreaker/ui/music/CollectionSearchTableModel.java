package com.ncmbreaker.ui.music;

import com.ncmbreaker.netease.music.MusicModels.SearchResult;
import com.ncmbreaker.netease.music.MusicModels.SearchType;
import javax.swing.table.AbstractTableModel;

final class CollectionSearchTableModel extends AbstractTableModel {
    private static final String[] COLUMNS = {"序号", "歌单", "创建者", "歌曲", "打开"};
    private SearchResult result;

    void setResult(SearchResult result) {
        this.result = result;
        fireTableDataChanged();
    }

    private boolean albums() { return result != null && result.type() == SearchType.ALBUM; }
    long id(int row) { return albums() ? result.albums().get(row).id() : result.playlists().get(row).id(); }
    @Override public int getRowCount() { return result == null || result.type() == SearchType.SONG ? 0 : result.size(); }
    @Override public int getColumnCount() { return COLUMNS.length; }
    @Override public String getColumnName(int column) {
        return albums() && column == 1 ? "专辑" : albums() && column == 2 ? "歌手" : COLUMNS[column];
    }
    @Override public boolean isCellEditable(int row, int column) { return column == 4; }
    @Override public Object getValueAt(int row, int column) {
        if (albums()) {
            var album = result.albums().get(row);
            return switch (column) {
                case 0 -> result.offset() + row + 1;
                case 1 -> album.name();
                case 2 -> album.artist();
                case 3 -> album.trackCount() + " 首";
                default -> "\u203a";
            };
        }
        var playlist = result.playlists().get(row);
        return switch (column) {
            case 0 -> result.offset() + row + 1;
            case 1 -> playlist.name();
            case 2 -> playlist.creator();
            case 3 -> playlist.trackCount() + " 首";
            default -> "\u203a";
        };
    }
}
