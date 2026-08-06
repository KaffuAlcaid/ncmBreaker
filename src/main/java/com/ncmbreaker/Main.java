package com.ncmbreaker;

import java.nio.file.Path;
import java.util.ArrayList;
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
        var exitCode = run(args);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int run(String[] args) {
        Path outputDirectory = Path.of("output");
        var inspectOnly = false;
        var inputs = new ArrayList<Path>();

        for (var index = 0; index < args.length; index++) {
            switch (args[index]) {
                case "-o", "--output" -> {
                    if (++index >= args.length) {
                        System.err.println("缺少输出目录。");
                        return 1;
                    }
                    outputDirectory = Path.of(args[index]);
                }
                case "--inspect" -> inspectOnly = true;
                case "-h", "--help" -> {
                    printUsage();
                    return 0;
                }
                default -> inputs.add(Path.of(args[index]));
            }
        }

        if (inputs.isEmpty()) {
            System.err.println("没有输入文件或目录。");
            return 1;
        }

        var fileIO = new NcmFileIO();
        var files = java.util.Set.<Path>of();
        try {
            files = fileIO.collectNcmFiles(inputs);
        } catch (Exception exception) {
            System.err.println("读取输入失败: " + exception.getMessage());
            return 2;
        }

        if (files.isEmpty()) {
            System.err.println("没有找到 .ncm 文件。");
            return 1;
        }

        var failed = 0;
        var completed = 0;
        for (var file : files) {
            var inspection = fileIO.inspect(file);
            printInspection(inspection);
            if (inspectOnly) {
                continue;
            }
            if (!inspection.convertible()) {
                failed++;
                continue;
            }

            try {
                var progress = new ConsoleProgress(file.getFileName().toString());
                var result = fileIO.decode(file, outputDirectory, progress::update);
                progress.finish();
                System.out.println("输出: " + result.output());
                completed++;
            } catch (Exception exception) {
                System.err.println("转换失败: " + file + " -> " + exception.getMessage());
                failed++;
            }
        }

        if (!inspectOnly) {
            System.out.printf("完成 %d 个，失败 %d 个。%n", completed, failed);
        }
        return failed == 0 ? 0 : 2;
    }

    private static void printInspection(NcmFileIO.Inspection inspection) {
        var metadata = inspection.metadata();
        System.out.printf("%n%s%n", inspection.source());
        System.out.printf("  类型: %s%n", inspection.profile());
        System.out.printf("  说明: %s%n", inspection.detail());
        if (metadata != null) {
            System.out.printf("  标记: %s%n", inspection.marker());
            System.out.printf("  歌曲: %s%n", metadata.title());
            System.out.printf("  歌手: %s%n", metadata.artistDisplay());
            System.out.printf("  专辑: %s%n", metadata.album());
            System.out.printf("  音频: %s，%,d bytes%n", inspection.audioFormat(), inspection.audioSize());
            System.out.printf("  封面: %s，%,d bytes%n", inspection.coverFormat(), inspection.coverSize());
        }
    }

    private static void printUsage() {
        System.out.println("用法:");
        System.out.println("  java -jar ncm-breaker.jar              打开图形界面");
        System.out.println("  java -jar ncm-breaker.jar [--inspect] [-o 输出目录] <文件或目录>...");
    }

    private static final class ConsoleProgress {
        private final String name;
        private int lastPercent = -1;

        private ConsoleProgress(String name) {
            this.name = name;
        }

        private void update(long completed, long total) {
            var percent = total == 0 ? 100 : (int) Math.min(100, completed * 100 / total);
            if (percent != lastPercent && (percent == 100 || percent / 10 != lastPercent / 10)) {
                System.out.printf("  转换 %s: %d%%%n", name, percent);
                lastPercent = percent;
            }
        }

        private void finish() {
            if (lastPercent != 100) {
                System.out.printf("  转换 %s: 100%%%n", name);
            }
        }
    }
}
