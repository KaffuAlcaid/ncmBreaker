package com.ncmbreaker.ui.music;

import com.ncmbreaker.netease.music.MusicModels.Song;
import javax.swing.table.AbstractTableModel;
import java.util.List;

final class MusicTableModel extends AbstractTableModel {
    private List<Song> songs = List.of();
    private static final String[] COLUMNS = {"序号", "歌曲", "歌手", "专辑", "时长", "下载"};
    void setSongs(List<Song> songs) { this.songs = List.copyOf(songs); fireTableDataChanged(); }
    Song song(int row) { return songs.get(row); }
    @Override public int getRowCount() { return songs.size(); }
    @Override public int getColumnCount() { return COLUMNS.length; }
    @Override public boolean isCellEditable(int row, int column) { return column == 5; }
    @Override public String getColumnName(int column) { return COLUMNS[column]; }
    @Override public Class<?> getColumnClass(int column) { return column == 0 ? Integer.class : String.class; }
    @Override public Object getValueAt(int row, int column) {
        var song = songs.get(row);
        return switch (column) {
            case 0 -> row + 1;
            case 1 -> song.title() + (song.detailAvailable() ? "" : "（详情暂不可用）");
            case 2 -> song.artistText();
            case 3 -> song.album();
            case 4 -> song.durationMillis() <= 0 ? "" : "%d:%02d".formatted(song.durationMillis() / 60000, song.durationMillis() / 1000 % 60);
            default -> "↓";
        };
    }
}
