package com.ncmbreaker.ui.music;

import com.ncmbreaker.netease.music.MusicModels.PlaylistContent;
import com.ncmbreaker.netease.music.MusicModels.SearchResult;
import com.ncmbreaker.netease.music.MusicModels.SearchType;
import javax.swing.RowSorter;
import java.util.List;

record MusicViewState(PlaylistContent content, SearchResult search, MusicViewState returnSearch,
                      List<Long> selectedSongs, List<RowSorter.SortKey> sortKeys,
                      String filter, int scroll, long selectedCollection,
                      String input, SearchType inputType, boolean keywordInput) {
    MusicViewState {
        selectedSongs = List.copyOf(selectedSongs);
        sortKeys = List.copyOf(sortKeys);
    }
}
