package com.ncmbreaker.ui.music;

import com.ncmbreaker.netease.music.MusicModels.Song;
import javax.swing.table.AbstractTableModel;
import java.util.List;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import javax.swing.event.TableModelEvent;

final class MusicTableModel extends AbstractTableModel {
    private List<Song> songs = List.of();
    private int offset;
    private final Set<Long> checked = new HashSet<>();
    private static final String[] COLUMNS = {"选择", "序号", "歌曲 / 歌手", "专辑", "时长", "下载"};
    void setSongs(List<Song> songs) { setSongs(songs, 0); }
    void setSongs(List<Song> songs, int offset) {
        this.songs = List.copyOf(songs); this.offset = offset; checked.clear(); fireTableDataChanged();
    }
    Song song(int row) { return songs.get(row); }
    List<Song> checkedSongs() { return songs.stream().filter(song -> checked.contains(song.id())).toList(); }
    boolean checked(int row) { return checked.contains(songs.get(row).id()); }
    void setChecked(Collection<Long> ids) {
        var selected = new HashSet<>(ids);
        selected.retainAll(songs.stream().map(Song::id).collect(java.util.stream.Collectors.toSet()));
        if (checked.equals(selected)) return;
        checked.clear(); checked.addAll(selected);
        if (!songs.isEmpty()) fireTableChanged(new TableModelEvent(this, 0, songs.size() - 1, 0));
    }
    @Override public int getRowCount() { return songs.size(); }
    @Override public int getColumnCount() { return COLUMNS.length; }
    @Override public boolean isCellEditable(int row, int column) { return column == 0 || column == 5; }
    @Override public String getColumnName(int column) { return COLUMNS[column]; }
    @Override public Class<?> getColumnClass(int column) {
        return column == 0 ? Boolean.class : column == 1 ? Integer.class : column == 2 ? Song.class : String.class;
    }
    @Override public void setValueAt(Object value, int row, int column) {
        if (column != 0 || !(value instanceof Boolean selected)) return;
        var ids = new HashSet<>(checked);
        if (selected) ids.add(song(row).id()); else ids.remove(song(row).id());
        setChecked(ids);
    }
    @Override public Object getValueAt(int row, int column) {
        var song = songs.get(row);
        return switch (column) {
            case 0 -> checked(row);
            case 1 -> offset + row + 1;
            case 2 -> song;
            case 3 -> song.album();
            case 4 -> song.durationMillis() <= 0 ? "" : "%d:%02d".formatted(song.durationMillis() / 60000, song.durationMillis() / 1000 % 60);
            default -> "↓";
        };
    }
}
