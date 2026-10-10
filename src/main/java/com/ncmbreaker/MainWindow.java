package com.ncmbreaker;

import com.ncmbreaker.ui.WorkspaceTabs;
import com.ncmbreaker.ui.account.AccountMenu;
import com.ncmbreaker.ui.music.MusicPanel;
import javax.swing.BorderFactory;
import javax.swing.AbstractButton;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellRenderer;
import javax.swing.plaf.basic.BasicButtonUI;
import javax.swing.plaf.basic.BasicComboBoxUI;
import javax.swing.plaf.basic.BasicGraphicsUtils;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

public final class MainWindow extends JFrame {
    private final NcmFileIO fileIO = new NcmFileIO();
    private final FileTableModel tableModel = new FileTableModel();
    private final Set<Path> knownPaths = new HashSet<>();

    private final JButton addFilesButton = button("添加文件");
    private final JButton addFolderButton = button("添加文件夹");
    private final JButton removeButton = button("移除所选");
    private final JButton clearButton = button("清空");
    private final JButton browseOutputButton = button("选择");
    private final JButton cancelButton = button("取消");
    private final JButton startButton = primaryButton("开始转换");
    private final JTextField outputField = new JTextField(defaultOutputPath());
    private final JCheckBox tagsCheckBox = new JCheckBox("写入基础标签", true);
    private final JCheckBox coverCheckBox = new JCheckBox("嵌入专辑封面", true);
    private final JComboBox<ConflictChoice> conflictBox = new JComboBox<>(ConflictChoice.values());
    private final JLabel summaryLabel = mutedLabel("0 个文件");
    private final JProgressBar overallProgress = new JProgressBar(0, 100);
    private final JTable table;
    private final JTabbedPane pages = new WorkspaceTabs();
    private final AccountMenu accountMenu = new AccountMenu();
    private final MusicPanel musicPanel = new MusicPanel();

    private SwingWorker<?, ?> scanWorker;
    private ConversionWorker conversionWorker;
    private boolean closing;

    public MainWindow() {
        super("NCM Breaker");
        table = createTable();
        musicPanel.setLoginAction(this::showAccountPage);
        accountMenu.setSessionListener(musicPanel::setSession);
        configureWindow();
        buildLayout();
        bindActions();
        updateControls();
        accountMenu.restoreLogin();
    }

    private void configureWindow() {
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                closeWindow();
            }

            @Override
            public void windowClosed(WindowEvent event) {
                musicPanel.close();
                accountMenu.close();
            }
        });
        setMinimumSize(new Dimension(960, 660));
        setSize(1100, 780);
        setLocationRelativeTo(null);
        setIconImages(List.of(
                createAppIcon(16),
                createAppIcon(32),
                createAppIcon(48),
                createAppIcon(64)
        ));
        getContentPane().setBackground(DarkTheme.WINDOW);
    }

    private void buildLayout() {
        var root = new JPanel(new BorderLayout());
        root.setBackground(DarkTheme.WINDOW);
        root.add(createHeader(), BorderLayout.NORTH);

        var content = new JPanel(new BorderLayout());
        content.setBackground(DarkTheme.WINDOW);
        content.add(createControls(), BorderLayout.NORTH);
        content.add(new JScrollPane(table), BorderLayout.CENTER);
        content.add(createFooter(), BorderLayout.SOUTH);
        pages.addTab("本地转换", content);
        pages.addTab("音乐下载", musicPanel);
        pages.addTab("下载任务", musicPanel.downloadView());
        var tasksLabel = new JLabel("下载任务  0");
        tasksLabel.setFont(pages.getFont());
        tasksLabel.setPreferredSize(new Dimension(122, 24));
        pages.setTabComponentAt(2, tasksLabel);
        musicPanel.setTaskCountListener(count -> tasksLabel.setText("下载任务  " + count));
        musicPanel.setShowDownloadsAction(() -> pages.setSelectedIndex(2));
        root.add(pages, BorderLayout.CENTER);
        setContentPane(root);
    }

    public void showAccountPage() {
        accountMenu.showAccount();
    }

    private JPanel createHeader() {
        var header = band(new BorderLayout(12, 0), DarkTheme.TITLE_BAR);
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(),
                BorderFactory.createEmptyBorder(18, 24, 12, 24)
        ));

        var brand = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        brand.setOpaque(false);
        var mark = new JLabel(new ImageIcon(createAppIcon(30)), SwingConstants.CENTER);
        mark.setPreferredSize(new Dimension(34, 34));
        brand.add(mark);

        var names = new JPanel(new BorderLayout());
        names.setOpaque(false);
        var title = new JLabel("NCM Breaker");
        title.setForeground(DarkTheme.TEXT);
        title.setFont(title.getFont().deriveFont(Font.PLAIN, 20f));
        names.add(title, BorderLayout.CENTER);
        brand.add(names);

        header.add(brand, BorderLayout.WEST);
        header.add(accountMenu, BorderLayout.EAST);
        return header;
    }

    private JPanel createControls() {
        var controls = new JPanel(new BorderLayout());
        controls.setBackground(DarkTheme.WINDOW);
        controls.add(createToolbar(), BorderLayout.NORTH);
        controls.add(createSettings(), BorderLayout.CENTER);
        return controls;
    }

    private JPanel createToolbar() {
        var toolbar = band(new BorderLayout(), DarkTheme.SURFACE);
        toolbar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, DarkTheme.BORDER),
                BorderFactory.createEmptyBorder(9, 12, 9, 12)
        ));
        var left = transparentFlow(FlowLayout.LEFT, 7);
        left.add(addFilesButton);
        left.add(addFolderButton);
        toolbar.add(left, BorderLayout.WEST);

        var right = transparentFlow(FlowLayout.RIGHT, 7);
        right.add(removeButton);
        right.add(clearButton);
        toolbar.add(right, BorderLayout.EAST);
        return toolbar;
    }

    private JPanel createSettings() {
        var settings = band(new GridBagLayout(), DarkTheme.SURFACE_SOFT);
        settings.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, DarkTheme.BORDER),
                BorderFactory.createEmptyBorder(10, 12, 10, 12)
        ));
        var constraints = new GridBagConstraints();
        constraints.anchor = GridBagConstraints.WEST;
        constraints.insets = new Insets(0, 0, 5, 8);
        constraints.gridx = 0;
        constraints.gridy = 0;
        settings.add(mutedLabel("输出目录"), constraints);

        constraints.gridy = 1;
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        settings.add(outputField, constraints);
        constraints.gridx = 1;
        constraints.weightx = 0;
        constraints.fill = GridBagConstraints.NONE;
        constraints.insets = new Insets(0, 0, 5, 0);
        settings.add(browseOutputButton, constraints);

        var options = transparentFlow(FlowLayout.LEFT, 14);
        tagsCheckBox.setOpaque(false);
        coverCheckBox.setOpaque(false);
        options.add(tagsCheckBox);
        options.add(coverCheckBox);
        options.add(mutedLabel("同名文件"));
        conflictBox.setUI(new DarkComboBoxUI());
        conflictBox.setBackground(DarkTheme.SURFACE);
        conflictBox.setForeground(DarkTheme.TEXT);
        conflictBox.setBorder(BorderFactory.createLineBorder(DarkTheme.BORDER));
        options.add(conflictBox);
        constraints.gridx = 0;
        constraints.gridy = 2;
        constraints.gridwidth = 2;
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.insets = new Insets(5, 0, 0, 0);
        settings.add(options, constraints);
        return settings;
    }

    private JPanel createFooter() {
        var footer = band(new BorderLayout(18, 0), DarkTheme.TITLE_BAR);
        footer.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, DarkTheme.BORDER),
                BorderFactory.createEmptyBorder(10, 12, 10, 12)
        ));

        var progressPanel = new JPanel(new BorderLayout(0, 5));
        progressPanel.setOpaque(false);
        progressPanel.add(summaryLabel, BorderLayout.NORTH);
        overallProgress.setStringPainted(true);
        overallProgress.setPreferredSize(new Dimension(500, 18));
        progressPanel.add(overallProgress, BorderLayout.CENTER);
        footer.add(progressPanel, BorderLayout.CENTER);

        var actions = transparentFlow(FlowLayout.RIGHT, 7);
        actions.add(cancelButton);
        actions.add(startButton);
        footer.add(actions, BorderLayout.EAST);
        return footer;
    }

    private JTable createTable() {
        var result = new JTable(tableModel) {
            @Override
            public String getToolTipText(MouseEvent event) {
                var row = rowAtPoint(event.getPoint());
                if (row < 0) {
                    return null;
                }
                var entry = tableModel.entryAt(convertRowIndexToModel(row));
                return entry.message == null || entry.message.isBlank()
                        ? entry.source.toString()
                        : entry.source + " — " + entry.message;
            }
        };
        result.setRowHeight(46);
        result.setBackground(DarkTheme.SURFACE);
        result.setForeground(DarkTheme.TEXT);
        result.setGridColor(DarkTheme.BORDER);
        result.setShowVerticalLines(false);
        result.setShowHorizontalLines(true);
        result.setFillsViewportHeight(true);
        result.setAutoCreateRowSorter(false);
        result.getTableHeader().setReorderingAllowed(false);
        result.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
        result.setDefaultRenderer(FileEntry.class, new FileRenderer());
        result.setDefaultRenderer(RowState.class, new StatusRenderer());
        result.getColumnModel().getColumn(0).setMinWidth(42);
        result.getColumnModel().getColumn(0).setMaxWidth(42);
        result.getColumnModel().getColumn(1).setPreferredWidth(390);
        result.getColumnModel().getColumn(2).setPreferredWidth(125);
        result.getColumnModel().getColumn(3).setPreferredWidth(65);
        result.getColumnModel().getColumn(4).setPreferredWidth(70);
        result.getColumnModel().getColumn(5).setPreferredWidth(85);
        result.getColumnModel().getColumn(6).setPreferredWidth(155);
        result.getTableHeader().addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (result.columnAtPoint(event.getPoint()) == 0 && !busy()) {
                    tableModel.toggleAll();
                }
            }
        });
        return result;
    }

    private void bindActions() {
        addFilesButton.addActionListener(event -> chooseFiles());
        addFolderButton.addActionListener(event -> chooseFolder());
        browseOutputButton.addActionListener(event -> chooseOutputDirectory());
        removeButton.addActionListener(event -> removeSelected());
        clearButton.addActionListener(event -> clearEntries());
        startButton.addActionListener(event -> startConversion());
        cancelButton.addActionListener(event -> cancelConversion());
        outputField.getDocument().addDocumentListener((SimpleDocumentListener) this::updateControls);
    }

    private void chooseFiles() {
        var chooser = ncmChooser();
        chooser.setMultiSelectionEnabled(true);
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            var roots = java.util.Arrays.stream(chooser.getSelectedFiles()).map(file -> file.toPath()).toList();
            scan(roots);
        }
    }

    private void chooseFolder() {
        var chooser = ncmChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            scan(List.of(chooser.getSelectedFile().toPath()));
        }
    }

    private void chooseOutputDirectory() {
        var chooser = new JFileChooser();
        chooser.setDialogTitle("选择输出目录");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            outputField.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private JFileChooser ncmChooser() {
        var chooser = new JFileChooser();
        chooser.setDialogTitle("选择 NCM 文件");
        chooser.setFileFilter(new FileNameExtensionFilter("网易云 NCM 文件", "ncm"));
        return chooser;
    }

    private void scan(List<Path> roots) {
        if (scanWorker != null || roots.isEmpty()) {
            return;
        }
        scanWorker = new SwingWorker<Void, FileEntry>() {
            @Override
            protected Void doInBackground() throws Exception {
                for (var path : fileIO.collectNcmFiles(roots)) {
                    if (isCancelled()) {
                        break;
                    }
                    var inspection = fileIO.inspect(path);
                    publish(new FileEntry(path, inspection));
                }
                return null;
            }

            @Override
            protected void process(List<FileEntry> entries) {
                for (var entry : entries) {
                    if (knownPaths.add(entry.source)) {
                        tableModel.add(entry);
                    }
                }
                updateControls();
            }

            @Override
            protected void done() {
                String errorMessage = null;
                try {
                    get();
                } catch (CancellationException ignored) {
                    // Closing the window may cancel an in-progress scan.
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    errorMessage = "文件扫描被中断。";
                } catch (ExecutionException exception) {
                    var cause = exception.getCause();
                    var detail = cause == null ? exception.getMessage() : cause.getMessage();
                    errorMessage = detail == null || detail.isBlank()
                            ? "读取文件或文件夹失败。"
                            : "读取文件或文件夹失败：" + detail;
                }
                scanWorker = null;
                updateControls();
                if (errorMessage != null && !closing) {
                    JOptionPane.showMessageDialog(
                            MainWindow.this,
                            errorMessage,
                            "扫描失败",
                            JOptionPane.ERROR_MESSAGE
                    );
                }
            }
        };
        scanWorker.execute();
        updateControls();
    }

    private void removeSelected() {
        var removed = tableModel.removeSelected();
        removed.forEach(entry -> knownPaths.remove(entry.source));
        updateControls();
    }

    private void clearEntries() {
        tableModel.clear();
        knownPaths.clear();
        updateControls();
    }

    private void startConversion() {
        var selected = tableModel.convertibleSelected();
        if (selected.isEmpty()) {
            return;
        }

        final Path outputDirectory;
        try {
            outputDirectory = Path.of(outputField.getText().trim());
        } catch (RuntimeException exception) {
            JOptionPane.showMessageDialog(this, "输出目录无效。", "无法开始", JOptionPane.ERROR_MESSAGE);
            return;
        }

        var choice = (ConflictChoice) conflictBox.getSelectedItem();
        var options = new NcmFileIO.DecodeOptions(
                tagsCheckBox.isSelected(),
                coverCheckBox.isSelected(),
                choice == null ? NcmFileIO.ConflictPolicy.RENAME : choice.policy
        );
        selected.forEach(entry -> entry.update(RowState.QUEUED, 0, "等待转换", null));
        tableModel.refresh();
        conversionWorker = new ConversionWorker(selected, outputDirectory, options);
        conversionWorker.execute();
        updateControls();
    }

    private void cancelConversion() {
        if (conversionWorker != null) {
            conversionWorker.requestCancellation();
            cancelButton.setEnabled(false);
        }
    }

    private void closeWindow() {
        closing = true;
        musicPanel.close();
        accountMenu.close();
        if (conversionWorker != null) {
            conversionWorker.requestCancellation();
            cancelButton.setEnabled(false);
            summaryLabel.setText("正在取消转换并清理临时文件");
            return;
        }
        if (scanWorker != null) {
            scanWorker.cancel(true);
        }
        dispose();
    }

    private void updateControls() {
        var busy = busy();
        var hasRows = tableModel.getRowCount() > 0;
        var hasSelected = !tableModel.convertibleSelected().isEmpty();
        addFilesButton.setEnabled(!busy);
        addFolderButton.setEnabled(!busy);
        removeButton.setEnabled(!busy && tableModel.hasSelected());
        clearButton.setEnabled(!busy && hasRows);
        outputField.setEnabled(!busy);
        browseOutputButton.setEnabled(!busy);
        tagsCheckBox.setEnabled(!busy);
        coverCheckBox.setEnabled(!busy);
        conflictBox.setEnabled(!busy);
        startButton.setEnabled(!busy && hasSelected && !outputField.getText().isBlank());
        cancelButton.setEnabled(conversionWorker != null && !conversionWorker.isDone());
        updateSummary();
    }

    private boolean busy() {
        return scanWorker != null || conversionWorker != null;
    }

    private void updateSummary() {
        var entries = tableModel.entries();
        var success = entries.stream().filter(entry -> entry.state == RowState.DONE).count();
        var failed = entries.stream().filter(entry -> entry.state == RowState.FAILED).count();
        var selected = entries.stream().filter(entry -> entry.selected && entry.inspection.convertible()).toList();
        var totalBytes = selected.stream().mapToLong(entry -> Math.max(1, entry.inspection.audioSize())).sum();
        var completedBytes = selected.stream()
                .mapToLong(entry -> Math.max(1, entry.inspection.audioSize()) * entry.progress / 100)
                .sum();
        var percent = totalBytes == 0 ? 0 : (int) Math.min(100, completedBytes * 100 / totalBytes);
        overallProgress.setValue(percent);
        overallProgress.setString(percent + "%");

        var summary = entries.size() + " 个文件";
        if (success > 0 || failed > 0) {
            summary += "  ·  已完成 " + success + "  ·  失败 " + failed;
        } else if (scanWorker != null) {
            summary += "  ·  正在检测";
        }
        summaryLabel.setText(summary);
    }

    private static String defaultOutputPath() {
        return Path.of(System.getProperty("user.home"), "Music", "NCM Output").toString();
    }

    private static BufferedImage createAppIcon(int size) {
        var image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.scale(size / 256.0, size / 256.0);

            graphics.setColor(DarkTheme.WINDOW);
            graphics.fillRoundRect(8, 8, 240, 240, 52, 52);

            graphics.setColor(DarkTheme.TITLE_BAR);
            graphics.fillRoundRect(51, 104, 154, 104, 28, 28);

            graphics.setColor(DarkTheme.PRIMARY);
            graphics.setStroke(new BasicStroke(14f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            graphics.drawRoundRect(58, 110, 140, 91, 22, 22);
            graphics.drawArc(72, 45, 116, 116, 180, -135);

            graphics.setColor(DarkTheme.WARNING);
            graphics.setStroke(new BasicStroke(12f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            graphics.drawLine(165, 60, 177, 72);

            graphics.setColor(DarkTheme.TEXT);
            graphics.setStroke(new BasicStroke(12f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            graphics.drawLine(96, 148, 96, 172);
            graphics.drawLine(128, 133, 128, 184);
            graphics.drawLine(160, 144, 160, 176);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private static String elide(String value, int limit) {
        if (value.length() <= limit) {
            return value;
        }
        var end = Math.max(8, limit / 3);
        return value.substring(0, limit - end - 1) + "…" + value.substring(value.length() - end);
    }

    private static JPanel band(java.awt.LayoutManager layout, Color background) {
        var panel = new JPanel(layout);
        panel.setBackground(background);
        return panel;
    }

    private static JPanel transparentFlow(int alignment, int gap) {
        var panel = new JPanel(new FlowLayout(alignment, gap, 0));
        panel.setOpaque(false);
        return panel;
    }

    private static JLabel mutedLabel(String text) {
        var label = new JLabel(text);
        label.setForeground(DarkTheme.MUTED);
        label.setFont(label.getFont().deriveFont(Font.PLAIN, 12f));
        return label;
    }

    private static JButton button(String text) {
        var button = new JButton(text);
        styleButton(button);
        return button;
    }

    private static void styleButton(JButton button) {
        button.setUI(new DarkButtonUI());
        button.setFocusPainted(false);
        button.setOpaque(true);
        button.setContentAreaFilled(true);
        button.setBackground(DarkTheme.SURFACE);
        button.setForeground(DarkTheme.TEXT);
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(DarkTheme.BORDER),
                BorderFactory.createEmptyBorder(6, 10, 6, 10)
        ));
    }

    private static final class DarkButtonUI extends BasicButtonUI {
        @Override
        protected void paintText(Graphics graphics, AbstractButton button, Rectangle textRect, String text) {
            graphics.setColor(button.isEnabled() ? button.getForeground() : DarkTheme.MUTED);
            var metrics = graphics.getFontMetrics();
            BasicGraphicsUtils.drawStringUnderlineCharAt(
                    graphics,
                    text,
                    button.getDisplayedMnemonicIndex(),
                    textRect.x,
                    textRect.y + metrics.getAscent()
            );
        }
    }

    private static JButton primaryButton(String text) {
        var button = button(text);
        button.setFont(button.getFont().deriveFont(Font.BOLD));
        button.setBackground(DarkTheme.PRIMARY);
        button.setForeground(DarkTheme.PRIMARY_TEXT);
        button.addPropertyChangeListener("enabled", event -> {
            button.setBackground(button.isEnabled() ? DarkTheme.PRIMARY : DarkTheme.SURFACE);
            button.setForeground(button.isEnabled() ? DarkTheme.PRIMARY_TEXT : DarkTheme.MUTED);
        });
        button.setOpaque(true);
        return button;
    }

    private static final class DarkComboBoxUI extends BasicComboBoxUI {
        @Override
        protected JButton createArrowButton() {
            var arrow = button("v");
            arrow.setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, DarkTheme.BORDER));
            arrow.setPreferredSize(new Dimension(28, 28));
            return arrow;
        }
    }

    private enum RowState {
        READY("待转换", DarkTheme.MUTED),
        QUEUED("等待", DarkTheme.MUTED),
        CONVERTING("转换中", DarkTheme.WARNING),
        DONE("已完成", DarkTheme.SUCCESS),
        SKIPPED("已跳过", DarkTheme.WARNING),
        FAILED("失败", DarkTheme.DANGER),
        CANCELLED("已取消", DarkTheme.DANGER),
        UNSUPPORTED("不支持", DarkTheme.DANGER);

        private final String text;
        private final Color color;

        RowState(String text, Color color) {
            this.text = text;
            this.color = color;
        }
    }

    private enum ConflictChoice {
        RENAME("自动编号，不覆盖", NcmFileIO.ConflictPolicy.RENAME),
        SKIP("跳过", NcmFileIO.ConflictPolicy.SKIP),
        OVERWRITE("覆盖", NcmFileIO.ConflictPolicy.OVERWRITE);

        private final String label;
        private final NcmFileIO.ConflictPolicy policy;

        ConflictChoice(String label, NcmFileIO.ConflictPolicy policy) {
            this.label = label;
            this.policy = policy;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private final class FileEntry {
        private final Path source;
        private final NcmFileIO.Inspection inspection;
        private boolean selected;
        private RowState state;
        private int progress;
        private String message;
        private Path output;

        private FileEntry(Path source, NcmFileIO.Inspection inspection) {
            this.source = source;
            this.inspection = inspection;
            selected = inspection.convertible();
            state = inspection.convertible() ? RowState.READY : RowState.UNSUPPORTED;
            message = inspection.detail();
        }

        private void update(RowState state, int progress, String message, Path output) {
            this.state = state;
            this.progress = Math.max(0, Math.min(100, progress));
            this.message = message;
            this.output = output;
        }
    }

    private final class FileTableModel extends AbstractTableModel {
        private final String[] columns = {"选", "文件", "检测类型", "格式", "封面", "大小", "状态"};
        private final List<FileEntry> entries = new ArrayList<>();

        @Override
        public int getRowCount() {
            return entries.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return switch (column) {
                case 0 -> Boolean.class;
                case 1 -> FileEntry.class;
                case 6 -> RowState.class;
                default -> String.class;
            };
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return column == 0 && !busy() && entries.get(row).inspection.convertible();
        }

        @Override
        public Object getValueAt(int row, int column) {
            var entry = entries.get(row);
            return switch (column) {
                case 0 -> entry.selected;
                case 1 -> entry;
                case 2 -> entry.inspection.profile().toString();
                case 3 -> entry.inspection.audioFormat().toUpperCase(java.util.Locale.ROOT);
                case 4 -> entry.inspection.coverFormat().toUpperCase(java.util.Locale.ROOT);
                case 5 -> formatBytes(entry.inspection.sourceSize());
                case 6 -> entry.state;
                default -> "";
            };
        }

        @Override
        public void setValueAt(Object value, int row, int column) {
            if (column == 0 && value instanceof Boolean selected) {
                entries.get(row).selected = selected;
                fireTableCellUpdated(row, column);
                updateControls();
            }
        }

        private FileEntry entryAt(int row) {
            return entries.get(row);
        }

        private List<FileEntry> entries() {
            return List.copyOf(entries);
        }

        private void add(FileEntry entry) {
            var index = entries.size();
            entries.add(entry);
            fireTableRowsInserted(index, index);
        }

        private boolean hasSelected() {
            return entries.stream().anyMatch(entry -> entry.selected);
        }

        private List<FileEntry> convertibleSelected() {
            return entries.stream().filter(entry -> entry.selected && entry.inspection.convertible()).toList();
        }

        private List<FileEntry> removeSelected() {
            var removed = entries.stream().filter(entry -> entry.selected).toList();
            entries.removeIf(entry -> entry.selected);
            fireTableDataChanged();
            return removed;
        }

        private void clear() {
            entries.clear();
            fireTableDataChanged();
        }

        private void toggleAll() {
            var selectable = entries.stream().filter(entry -> entry.inspection.convertible()).toList();
            var select = selectable.stream().anyMatch(entry -> !entry.selected);
            selectable.forEach(entry -> entry.selected = select);
            fireTableDataChanged();
            updateControls();
        }

        private void refresh() {
            fireTableDataChanged();
            updateSummary();
        }
    }

    private final class FileRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(
                JTable table,
                Object value,
                boolean selected,
                boolean focused,
                int row,
                int column
        ) {
            var label = (JLabel) super.getTableCellRendererComponent(table, value, selected, focused, row, column);
            var entry = (FileEntry) value;
            label.setText(elide(entry.source.getFileName().toString(), 58));
            label.setForeground(selected ? table.getSelectionForeground() : DarkTheme.TEXT);
            label.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
            return label;
        }
    }

    private final class StatusRenderer implements TableCellRenderer {
        private final JPanel panel = new JPanel(new BorderLayout(0, 3));
        private final JLabel label = new JLabel();
        private final JProgressBar progress = new JProgressBar(0, 100);

        private StatusRenderer() {
            panel.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
            progress.setPreferredSize(new Dimension(120, 5));
            progress.setBorderPainted(false);
            panel.add(label, BorderLayout.CENTER);
            panel.add(progress, BorderLayout.SOUTH);
        }

        @Override
        public Component getTableCellRendererComponent(
                JTable table,
                Object value,
                boolean selected,
                boolean focused,
                int row,
                int column
        ) {
            var entry = tableModel.entryAt(table.convertRowIndexToModel(row));
            label.setText(entry.state.text + (entry.state == RowState.CONVERTING ? "  " + entry.progress + "%" : ""));
            label.setForeground(entry.state.color);
            progress.setValue(entry.progress);
            progress.setVisible(entry.state == RowState.CONVERTING || entry.state == RowState.DONE);
            panel.setBackground(selected ? table.getSelectionBackground() : table.getBackground());
            return panel;
        }
    }

    private record ProgressUpdate(
            FileEntry entry,
            RowState state,
            int progress,
            String message,
            Path output
    ) {
    }

    private final class ConversionWorker extends SwingWorker<Void, ProgressUpdate> {
        private final List<FileEntry> entries;
        private final Path outputDirectory;
        private final NcmFileIO.DecodeOptions options;
        private volatile boolean cancellationRequested;
        private volatile Thread workerThread;

        private ConversionWorker(
                List<FileEntry> entries,
                Path outputDirectory,
                NcmFileIO.DecodeOptions options
        ) {
            this.entries = List.copyOf(entries);
            this.outputDirectory = outputDirectory;
            this.options = options;
        }

        @Override
        protected Void doInBackground() {
            workerThread = Thread.currentThread();
            try {
                for (var entry : entries) {
                    if (cancellationRequested) {
                        break;
                    }
                    publish(new ProgressUpdate(entry, RowState.CONVERTING, 0, "正在转换", null));
                    var lastProgress = new int[]{-1};
                    try {
                        var result = fileIO.decode(entry.source, outputDirectory, options, (completed, total) -> {
                            if (cancellationRequested) {
                                Thread.currentThread().interrupt();
                            }
                            var percent = total == 0 ? 100 : (int) Math.min(100, completed * 100 / total);
                            if (percent != lastProgress[0]) {
                                lastProgress[0] = percent;
                                publish(new ProgressUpdate(entry, RowState.CONVERTING, percent, "正在转换", null));
                            }
                        });
                        if (result.outcome() == NcmFileIO.DecodeOutcome.SKIPPED) {
                            publish(new ProgressUpdate(entry, RowState.SKIPPED, 100, "目标文件已存在", result.output()));
                        } else {
                            var message = "flac".equals(result.audioFormat()) && (options.writeBasicTags() || options.embedCover())
                                    ? "已完成，FLAC 标签保持原样"
                                    : "已完成";
                            publish(new ProgressUpdate(entry, RowState.DONE, 100, message, result.output()));
                        }
                    } catch (CancellationException exception) {
                        publish(new ProgressUpdate(entry, RowState.CANCELLED, entry.progress, "已取消", null));
                        break;
                    } catch (Exception exception) {
                        publish(new ProgressUpdate(entry, RowState.FAILED, entry.progress, exception.getMessage(), null));
                    }
                }
                return null;
            } finally {
                workerThread = null;
            }
        }

        private void requestCancellation() {
            cancellationRequested = true;
            var thread = workerThread;
            if (thread != null) {
                thread.interrupt();
            }
        }

        @Override
        protected void process(List<ProgressUpdate> updates) {
            for (var update : updates) {
                update.entry.update(update.state, update.progress, update.message, update.output);
            }
            tableModel.refresh();
        }

        @Override
        protected void done() {
            if (cancellationRequested) {
                entries.stream()
                        .filter(entry -> entry.state == RowState.QUEUED)
                        .forEach(entry -> entry.update(RowState.CANCELLED, 0, "已取消", null));
            }
            conversionWorker = null;
            tableModel.refresh();
            updateControls();
            if (closing) {
                dispose();
            }
        }
    }

    @FunctionalInterface
    private interface SimpleDocumentListener extends javax.swing.event.DocumentListener {
        void changed();

        @Override
        default void insertUpdate(javax.swing.event.DocumentEvent event) {
            changed();
        }

        @Override
        default void removeUpdate(javax.swing.event.DocumentEvent event) {
            changed();
        }

        @Override
        default void changedUpdate(javax.swing.event.DocumentEvent event) {
            changed();
        }
    }
}
