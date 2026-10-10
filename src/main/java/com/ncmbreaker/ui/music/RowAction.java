package com.ncmbreaker.ui.music;

import javax.swing.AbstractCellEditor;
import javax.swing.JButton;
import javax.swing.JTable;
import javax.swing.BorderFactory;
import javax.swing.plaf.basic.BasicButtonUI;
import javax.swing.table.TableCellEditor;
import javax.swing.table.TableCellRenderer;
import java.awt.Component;
import java.util.function.IntConsumer;

final class RowAction extends AbstractCellEditor implements TableCellRenderer, TableCellEditor {
    private final JButton renderer = button();
    private final JButton editor = button();
    private int modelRow;
    RowAction(IntConsumer action) {
        editor.addActionListener(event -> {
            int row = modelRow;
            fireEditingStopped();
            action.accept(row);
        });
    }
    private static JButton button() {
        var button = new JButton("↓");
        button.setUI(new BasicButtonUI());
        button.setFont(new java.awt.Font(java.awt.Font.DIALOG, java.awt.Font.PLAIN, 20));
        button.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        button.setToolTipText("选择音质并下载");
        button.setFocusPainted(false);
        return button;
    }
    @Override public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focused, int row, int column) {
        renderer.setText(value.toString());
        renderer.setEnabled(table.isEnabled());
        renderer.setBackground(selected ? table.getSelectionBackground() : table.getBackground());
        return renderer;
    }
    @Override public Component getTableCellEditorComponent(JTable table, Object value, boolean selected, int row, int column) {
        modelRow = table.convertRowIndexToModel(row);
        editor.setText(value.toString());
        editor.setBackground(table.getSelectionBackground());
        return editor;
    }
    @Override public Object getCellEditorValue() { return "↓"; }
}
