package com.ncmbreaker;

import javax.swing.UIManager;
import javax.swing.plaf.ColorUIResource;
import javax.swing.plaf.FontUIResource;
import java.awt.Color;
import java.awt.Font;

final class DarkTheme {
    static final Color WINDOW = new Color(0x17, 0x1a, 0x1c);
    static final Color TITLE_BAR = new Color(0x20, 0x24, 0x27);
    static final Color SURFACE = new Color(0x22, 0x27, 0x2a);
    static final Color SURFACE_SOFT = new Color(0x1c, 0x20, 0x22);
    static final Color TEXT = new Color(0xed, 0xf1, 0xf2);
    static final Color MUTED = new Color(0xa7, 0xb1, 0xb6);
    static final Color BORDER = new Color(0x3b, 0x44, 0x48);
    static final Color PRIMARY = new Color(0x4e, 0xc8, 0xc1);
    static final Color PRIMARY_TEXT = new Color(0x09, 0x22, 0x20);
    static final Color SUCCESS = new Color(0x64, 0xcf, 0x8d);
    static final Color WARNING = new Color(0xf0, 0xaa, 0x43);
    static final Color DANGER = new Color(0xff, 0x8a, 0x82);

    private DarkTheme() {
    }

    static void install() {
        try {
            UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
        } catch (Exception exception) {
            throw new IllegalStateException("无法初始化 Swing 外观", exception);
        }

        var defaults = UIManager.getDefaults();
        defaults.put("Button.gradient", java.util.List.of(
                0.3f,
                0.0f,
                resource(SURFACE),
                resource(SURFACE),
                resource(SURFACE)
        ));
        defaults.put("control", resource(SURFACE));
        defaults.put("info", resource(SURFACE));
        defaults.put("nimbusBase", resource(SURFACE_SOFT));
        defaults.put("nimbusAlertYellow", resource(WARNING));
        defaults.put("nimbusDisabledText", resource(MUTED.darker()));
        defaults.put("nimbusFocus", resource(PRIMARY));
        defaults.put("nimbusLightBackground", resource(SURFACE));
        defaults.put("nimbusSelectionBackground", resource(new Color(0x2f, 0x65, 0x66)));
        defaults.put("text", resource(TEXT));

        putColor("Panel.background", WINDOW);
        putColor("TabbedPane.background", WINDOW);
        putColor("TabbedPane.foreground", TEXT);
        putColor("TabbedPane.selected", SURFACE);
        putColor("TabbedPane.contentAreaColor", WINDOW);
        putColor("TabbedPane.focus", PRIMARY);
        putColor("TabbedPane.unselectedBackground", SURFACE_SOFT);
        putColor("TabbedPane.tabAreaBackground", WINDOW);
        putColor("TabbedPane.highlight", BORDER);
        putColor("TabbedPane.light", BORDER);
        putColor("TabbedPane.shadow", BORDER);
        putColor("TabbedPane.darkShadow", BORDER);
        putColor("TabbedPane.borderHightlightColor", BORDER);
        putColor("TabbedPane.selectHighlight", PRIMARY);
        defaults.put("TabbedPane.tabInsets", new java.awt.Insets(8, 14, 8, 14));
        putColor("Label.foreground", TEXT);
        putColor("Button.background", SURFACE);
        putColor("Button.foreground", TEXT);
        putColor("Button.disabledText", MUTED);
        putColor("Button.select", SURFACE_SOFT);
        putColor("CheckBox.background", SURFACE_SOFT);
        putColor("CheckBox.foreground", TEXT);
        putColor("RadioButton.background", WINDOW);
        putColor("RadioButton.foreground", TEXT);
        putColor("RadioButton.disabledText", MUTED);
        putColor("ComboBox.background", SURFACE);
        putColor("ComboBox.foreground", TEXT);
        putColor("ComboBox.selectionBackground", new Color(0x2f, 0x65, 0x66));
        putColor("ComboBox.selectionForeground", TEXT);
        putColor("TextField.background", SURFACE);
        putColor("TextField.foreground", TEXT);
        putColor("TextField.caretForeground", TEXT);
        putColor("TextField.inactiveForeground", MUTED);
        putColor("Table.background", SURFACE);
        putColor("Table.foreground", TEXT);
        putColor("Table.gridColor", BORDER);
        putColor("Table.selectionBackground", new Color(0x2b, 0x3d, 0x40));
        putColor("Table.selectionForeground", TEXT);
        putColor("TableHeader.background", SURFACE_SOFT);
        putColor("TableHeader.foreground", MUTED);
        putColor("ScrollPane.background", SURFACE);
        putColor("ScrollBar.background", SURFACE_SOFT);
        putColor("ScrollBar.thumb", BORDER);
        putColor("ProgressBar.background", BORDER);
        putColor("ProgressBar.foreground", PRIMARY);
        putColor("ProgressBar.selectionBackground", PRIMARY_TEXT);
        putColor("ProgressBar.selectionForeground", TEXT);
        putColor("ToolTip.background", SURFACE);
        putColor("ToolTip.foreground", TEXT);
        putColor("OptionPane.background", WINDOW);
        putColor("OptionPane.messageForeground", TEXT);
        putColor("FileChooser.background", WINDOW);
        putColor("List.background", SURFACE);
        putColor("List.foreground", TEXT);
        putColor("List.selectionBackground", new Color(0x2f, 0x65, 0x66));
        putColor("List.selectionForeground", TEXT);

        var baseFont = new Font("Microsoft YaHei UI", Font.PLAIN, 13);
        if (baseFont.canDisplayUpTo("中文日文かな") >= 0) {
            baseFont = new Font(Font.DIALOG, Font.PLAIN, 13);
        }
        var font = new FontUIResource(baseFont);
        for (var key : defaults.keySet()) {
            if (key.toString().endsWith(".font")) {
                defaults.put(key, font);
            }
        }
    }

    private static void putColor(String key, Color color) {
        UIManager.put(key, resource(color));
    }

    private static ColorUIResource resource(Color color) {
        return new ColorUIResource(color);
    }
}
