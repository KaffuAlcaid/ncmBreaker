package com.ncmbreaker.ui;

import javax.swing.*;
import javax.swing.plaf.basic.BasicButtonUI;
import javax.swing.plaf.basic.BasicGraphicsUtils;
import javax.swing.plaf.basic.BasicArrowButton;
import javax.swing.plaf.basic.BasicComboBoxUI;
import javax.swing.plaf.basic.BasicScrollBarUI;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;

public final class UiStyle {
    private UiStyle() { }

    public static Color muted() { return UIManager.getColor("TextField.inactiveForeground"); }
    public static Color border() { return UIManager.getColor("Table.gridColor"); }

    public static void button(AbstractButton button) {
        button.setUI(new BasicButtonUI() {
            @Override protected void paintText(Graphics graphics, AbstractButton target, Rectangle bounds, String text) {
                graphics.setColor(target.isEnabled() ? target.getForeground() : muted());
                BasicGraphicsUtils.drawStringUnderlineCharAt(graphics, text, target.getDisplayedMnemonicIndex(),
                        bounds.x, bounds.y + graphics.getFontMetrics().getAscent());
            }
        });
        button.setFocusPainted(true);
        button.setMargin(new Insets(0, 0, 0, 0));
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(border()),
                BorderFactory.createEmptyBorder(8, 12, 8, 12)));
        button.putClientProperty("html.disable", Boolean.TRUE);
    }

    public static JButton button(String text) {
        var button = new JButton(text);
        button(button);
        return button;
    }

    public static JTextField searchField(String hint) {
        var field = new JTextField() {
            @Override protected void paintComponent(Graphics graphics) {
                super.paintComponent(graphics);
                if (getText().isEmpty() && !hasFocus()) {
                    graphics.setColor(muted());
                    graphics.setFont(getFont());
                    var metrics = graphics.getFontMetrics();
                    graphics.drawString((String) getClientProperty("search.hint"), getInsets().left,
                            (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent());
                }
            }
        };
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(border()),
                BorderFactory.createEmptyBorder(9, 12, 9, 12)));
        searchHint(field, hint);
        return field;
    }

    public static void searchHint(JTextField field, String hint) {
        field.putClientProperty("search.hint", hint);
        field.setToolTipText(hint);
        field.getAccessibleContext().setAccessibleName(hint);
        field.repaint();
    }

    public static JScrollPane scrollPane(Component content) {
        var scroll = new JScrollPane(content);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(UIManager.getColor("Panel.background"));
        for (var bar : new JScrollBar[]{scroll.getVerticalScrollBar(), scroll.getHorizontalScrollBar()}) {
            bar.setPreferredSize(bar.getOrientation() == JScrollBar.VERTICAL
                    ? new Dimension(10, 0) : new Dimension(0, 10));
            bar.setUI(new BasicScrollBarUI() {
                @Override protected void configureScrollBarColors() {
                    thumbColor = border();
                    trackColor = UIManager.getColor("Panel.background");
                }
                @Override protected JButton createDecreaseButton(int direction) { return emptyButton(); }
                @Override protected JButton createIncreaseButton(int direction) { return emptyButton(); }
                private JButton emptyButton() {
                    var button = new JButton();
                    button.setPreferredSize(new Dimension(0, 0));
                    button.setMinimumSize(new Dimension(0, 0));
                    return button;
                }
                @Override protected void paintThumb(Graphics graphics, JComponent component, Rectangle bounds) {
                    graphics.setColor(thumbColor);
                    graphics.fillRoundRect(bounds.x + 2, bounds.y + 2,
                            Math.max(0, bounds.width - 4), Math.max(0, bounds.height - 4), 4, 4);
                }
            });
        }
        return scroll;
    }

    public static void comboBox(JComboBox<?> combo) {
        combo.setBorder(BorderFactory.createLineBorder(border()));
        combo.setUI(new BasicComboBoxUI() {
            @Override protected JButton createArrowButton() {
                var button = new BasicArrowButton(SwingConstants.SOUTH,
                        combo.getBackground(), combo.getBackground(), muted(), combo.getBackground());
                button.setBorder(BorderFactory.createEmptyBorder());
                return button;
            }
        });
    }

    public static void table(JTable table, int rowHeight) {
        table.setRowHeight(rowHeight);
        table.setFillsViewportHeight(true);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setShowVerticalLines(false);
        table.setShowHorizontalLines(true);
        table.setIntercellSpacing(new Dimension(0, 1));
        table.setBackground(UIManager.getColor("Panel.background"));
        table.getTableHeader().setReorderingAllowed(false);
        table.getTableHeader().setPreferredSize(new Dimension(0, 38));
        var header = new DefaultTableCellRenderer();
        header.setHorizontalAlignment(SwingConstants.LEFT);
        header.setBackground(table.getBackground());
        header.setForeground(muted());
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, border()),
                BorderFactory.createEmptyBorder(0, 12, 0, 12)));
        table.getTableHeader().setDefaultRenderer(header);
        table.setDefaultRenderer(Object.class, textRenderer());
        table.setDefaultRenderer(String.class, textRenderer());
    }

    public static DefaultTableCellRenderer textRenderer() {
        return new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable table, Object value,
                    boolean selected, boolean focus, int row, int column) {
                putClientProperty("html.disable", Boolean.TRUE);
                super.getTableCellRendererComponent(table, value, selected, focus, row, column);
                setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 12));
                setToolTipText(value == null ? null : value.toString());
                return this;
            }
        };
    }
}
