package com.ncmbreaker.ui.music;

import com.ncmbreaker.app.AppSettingsStore;
import com.ncmbreaker.netease.auth.LoginSession;
import com.ncmbreaker.netease.music.MusicModels.SearchType;
import com.ncmbreaker.netease.music.MusicService;
import com.ncmbreaker.ui.UiStyle;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.*;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

final class SearchSuggestions implements AutoCloseable {
    private record Item(String query, boolean history) { }
    private final JTextField field;
    private final AppSettingsStore settings;
    private final Supplier<LoginSession> session;
    private final Supplier<SearchType> type;
    private final BooleanSupplier enabled;
    private final Runnable submit;
    private final JPopupMenu popup = new JPopupMenu();
    private final DefaultListModel<Item> model = new DefaultListModel<>();
    private final JList<Item> list = new JList<>(model);
    private final Timer timer = new Timer(350, event -> request());
    private SwingWorker<List<String>, Void> worker;
    private long generation;
    private boolean changing;
    private boolean closed;

    SearchSuggestions(JTextField field, AppSettingsStore settings, Supplier<LoginSession> session,
                      Supplier<SearchType> type, BooleanSupplier enabled, Runnable submit) {
        this.field = field; this.settings = settings; this.session = session; this.type = type; this.enabled = enabled; this.submit = submit;
        timer.setRepeats(false); popup.setFocusable(false); popup.setLayout(new BorderLayout());
        list.setFocusable(false); list.setFixedCellHeight(36); list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> source, Object value, int index, boolean selected, boolean focus) {
                var component = super.getListCellRendererComponent(source, value, index, selected, focus);
                var item = (Item) value; setText((item.history() ? "历史 · " : "") + item.query());
                putClientProperty("html.disable", true); setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10)); return component;
            }
        });
        var scroll = UiStyle.scrollPane(list); popup.add(scroll, BorderLayout.CENTER);
        var clear = UiStyle.button("清除搜索历史"); clear.setFocusable(false);
        clear.addActionListener(event -> { settings.clearSearchHistory(type.get()); hide(); }); popup.add(clear, BorderLayout.SOUTH);
        list.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) {
                int index = list.locationToIndex(event.getPoint());
                if (index >= 0 && list.getCellBounds(index, index).contains(event.getPoint())) { list.setSelectedIndex(index); accept(); }
            }
        });
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { refresh(); }
            @Override public void removeUpdate(DocumentEvent event) { refresh(); }
            @Override public void changedUpdate(DocumentEvent event) { refresh(); }
        });
        field.addFocusListener(new FocusAdapter() {
            @Override public void focusGained(FocusEvent event) { refresh(); }
            @Override public void focusLost(FocusEvent event) { hide(); }
        });
        bind("DOWN", "suggestion-next", () -> move(1)); bind("UP", "suggestion-previous", () -> move(-1));
        bind("ESCAPE", "suggestion-close", this::hide);
        bind("ENTER", "suggestion-search", this::accept);
    }

    private void bind(String key, String name, Runnable action) {
        field.getInputMap().put(KeyStroke.getKeyStroke(key), name);
        field.getActionMap().put(name, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { action.run(); }
        });
    }
    private void move(int direction) {
        if (!popup.isVisible()) refresh();
        if (model.isEmpty()) return;
        int index = list.getSelectedIndex();
        list.setSelectedIndex(Math.floorMod(index < 0 ? direction > 0 ? 0 : model.size() - 1 : index + direction, model.size()));
        list.ensureIndexIsVisible(list.getSelectedIndex());
    }
    private void accept() {
        var selected = popup.isVisible() ? list.getSelectedValue() : null;
        hide(); changing = true;
        try { if (selected != null) field.setText(selected.query()); }
        finally { changing = false; }
        submit.run();
    }
    void hide() {
        generation++; timer.stop();
        if (worker != null) worker.cancel(true);
        worker = null; popup.setVisible(false);
    }
    void refresh() {
        if (changing || closed) return;
        hide(); model.clear();
        if (!enabled.getAsBoolean() || !field.hasFocus()) return;
        var query = field.getText().strip().toLowerCase(Locale.ROOT);
        settings.searchHistory(type.get()).stream().filter(value -> value.toLowerCase(Locale.ROOT).contains(query))
                .limit(8).forEach(value -> model.addElement(new Item(value, true)));
        show(); if (!query.isEmpty() && session.get() != null) timer.restart();
    }
    private void request() {
        if (closed || !enabled.getAsBoolean() || session.get() == null) return;
        long current = generation; var account = session.get(); var selectedType = type.get(); var query = field.getText().strip();
        worker = new SwingWorker<>() {
            @Override protected List<String> doInBackground() throws Exception { return new MusicService(account).suggestions(query, selectedType); }
            @Override protected void done() {
                if (closed || current != generation || account != session.get() || selectedType != type.get()) return;
                worker = null;
                try {
                    for (var value : get()) {
                        boolean exists = false;
                        for (int i = 0; i < model.size(); i++) if (model.get(i).query().equals(value)) exists = true;
                        if (!exists && model.size() < 10) model.addElement(new Item(value, false));
                    }
                    show();
                } catch (Exception ignored) { }
            }
        };
        worker.execute();
    }
    private void show() {
        if (model.isEmpty() || !field.isShowing() || !field.hasFocus()) return;
        popup.setPopupSize(Math.max(260, field.getWidth()), Math.min(8, model.size()) * 36 + 42);
        popup.show(field, 0, field.getHeight());
    }
    @Override public void close() { closed = true; hide(); }
}
