package com.ncmbreaker.ui;

import com.ncmbreaker.audio.OutputPlanner;
import com.ncmbreaker.audio.ProcessingOptions;
import com.ncmbreaker.audio.AudioProcessingService;
import com.ncmbreaker.netease.auth.LoginSession;
import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Path;
import java.util.List;

public final class ConversionOptionsDialog extends JDialog {
    public record Selection(Path directory, ProcessingOptions options, List<ProcessingOptionsPanel.PreviewItem> files,
                            AudioProcessingService processing) { }
    private Selection selection;
    private SwingWorker<?, ?> worker;

    public ConversionOptionsDialog(Window owner, ProcessingOptions initial, Path directory,
                                   List<ProcessingOptionsPanel.PreviewItem> files, LoginSession session, long totalBytes, long largestFile) {
        super(owner, "确认转换", ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        var root = new JPanel(new BorderLayout(0, 12));
        root.setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));
        var title = new JLabel("转换 " + files.size() + " 个文件，音频共 %.1f MB".formatted(totalBytes / 1048576.0));
        title.setFont(title.getFont().deriveFont(17f)); root.add(title, BorderLayout.NORTH);
        var processing = new ProcessingOptionsPanel(initial, directory, files, "本地转换", true,
                session != null && session.account() != null, true);
        var preparation = new AudioProcessingService(session);
        var scroll = UiStyle.scrollPane(processing); scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(18); root.add(scroll, BorderLayout.CENTER);
        var footer = new JPanel(new BorderLayout(0, 8));
        var status = new JLabel(" "); status.putClientProperty("html.disable", Boolean.TRUE);
        footer.add(status, BorderLayout.NORTH);
        var actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        var cancel = UiStyle.button("取消"); cancel.addActionListener(event -> dispose());
        var start = UiStyle.button("开始转换");
        start.addActionListener(event -> {
            final Selection selected;
            try { selected = new Selection(processing.directory(), processing.options(), processing.items(), preparation); }
            catch (RuntimeException exception) { status.setText(exception.getMessage()); return; }
            start.setEnabled(false); status.setText("正在检查保存目录与可用空间");
            processing.setBusy(true);
            worker = new SwingWorker<Void, Void>() {
                @Override protected Void doInBackground() throws Exception {
                    OutputPlanner.preflightBatch(selected.directory(), totalBytes,
                            selected.options().needsTags() || selected.options().exportCover() ? largestFile : 0); return null;
                }
                @Override protected void done() {
                    if (!isDisplayable()) return;
                    worker = null;
                    processing.setBusy(false);
                    try { get(); selection = selected; dispose(); }
                    catch (Exception exception) {
                        status.setText(com.ncmbreaker.netease.music.MusicException.describe(exception));
                        start.setEnabled(processing.validation().isBlank());
                    }
                }
            };
            worker.execute();
        });
        processing.addChangeListener(() -> start.setEnabled(worker == null && processing.validation().isBlank()));
        processing.setPreparationService(preparation);
        actions.add(cancel); actions.add(start); footer.add(actions, BorderLayout.SOUTH); root.add(footer, BorderLayout.SOUTH);
        setContentPane(root); getRootPane().setDefaultButton(start);
        setMinimumSize(new Dimension(560, 480));
        var available = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        setSize(Math.min(700, available.width - 40), Math.min(820, available.height - 60)); setLocationRelativeTo(owner);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent event) { processing.close(); if (worker != null) worker.cancel(true); }
        });
    }
    public Selection selection() { return selection; }
}
