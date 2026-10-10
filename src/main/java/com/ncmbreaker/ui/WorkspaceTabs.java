package com.ncmbreaker.ui;

import javax.swing.JTabbedPane;
import javax.swing.UIManager;
import javax.swing.plaf.basic.BasicTabbedPaneUI;
import java.awt.*;

public final class WorkspaceTabs extends JTabbedPane {
    public WorkspaceTabs() {
        setOpaque(true);
        setFont(getFont().deriveFont(15f));
        setUI(new BasicTabbedPaneUI() {
            @Override protected void installDefaults() {
                super.installDefaults();
                tabInsets = new Insets(12, 20, 12, 20);
                tabAreaInsets = new Insets(0, 12, 0, 12);
                contentBorderInsets = new Insets(1, 0, 0, 0);
                selectedTabPadInsets = new Insets(0, 0, 0, 0);
            }

            @Override protected void paintTabBackground(Graphics g, int placement, int index,
                    int x, int y, int width, int height, boolean selected) {
                g.setColor(tabPane.getBackground());
                g.fillRect(x, y, width, height);
            }

            @Override protected void paintTabBorder(Graphics g, int placement, int index,
                    int x, int y, int width, int height, boolean selected) {
                if (selected) {
                    g.setColor(UIManager.getColor("ProgressBar.foreground"));
                    g.fillRect(x + 16, y + height - 3, width - 32, 2);
                }
            }

            @Override protected void paintContentBorder(Graphics g, int placement, int selected) {
                int top = calculateTabAreaHeight(placement, runCount, maxTabHeight);
                g.setColor(UiStyle.border());
                g.drawLine(0, top, tabPane.getWidth(), top);
            }

            @Override protected void paintFocusIndicator(Graphics g, int placement, Rectangle[] rects,
                    int index, Rectangle icon, Rectangle text, boolean selected) {
                if (selected && tabPane.hasFocus()) {
                    g.setColor(UIManager.getColor("ProgressBar.foreground"));
                    g.drawRect(text.x - 4, text.y - 3, text.width + 8, text.height + 6);
                }
            }

            @Override protected int getTabLabelShiftX(int placement, int index, boolean selected) { return 0; }
            @Override protected int getTabLabelShiftY(int placement, int index, boolean selected) { return 0; }
        });
        setBackground(UIManager.getColor("TabbedPane.tabAreaBackground"));
    }
}
