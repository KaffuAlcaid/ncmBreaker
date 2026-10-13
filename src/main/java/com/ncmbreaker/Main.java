package com.ncmbreaker;

import javax.swing.SwingUtilities;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        if (args.length == 0 || (args.length == 1 && "--gui".equals(args[0]))) {
            DarkTheme.install();
            SwingUtilities.invokeLater(() -> new MainWindow().setVisible(true));
            return;
        }
        var exitCode = CliMain.run(args, "ncm-breaker.jar");
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }
}
