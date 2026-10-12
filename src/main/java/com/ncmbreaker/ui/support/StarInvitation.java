package com.ncmbreaker.ui.support;

import com.google.gson.Gson;
import com.ncmbreaker.app.ApplicationPaths;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.MenuSelectionManager;
import javax.swing.Timer;
import java.awt.Desktop;
import java.awt.Dialog;
import java.awt.Window;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.function.BooleanSupplier;

public final class StarInvitation implements AutoCloseable {
    private static final URI PROJECT_URI = URI.create("https://github.com/KaffuAlcaid/ncmBreaker");
    private final JFrame owner;
    private final BooleanSupplier busy;
    private final Timer retry;
    private boolean handled;
    private boolean closed;

    public StarInvitation(JFrame owner, BooleanSupplier busy) {
        this.owner = owner;
        this.busy = busy;
        retry = new Timer(1000, event -> offerWhenIdle());
    }

    public void taskSucceeded() {
        if (!closed && !handled && !retry.isRunning()) retry.start();
    }

    private void offerWhenIdle() {
        if (closed || !owner.isDisplayable()) {
            retry.stop();
            return;
        }
        if (!owner.isActive() || busy.getAsBoolean()
                || MenuSelectionManager.defaultManager().getSelectedPath().length > 0) return;
        for (var window : Window.getWindows()) {
            if (window instanceof Dialog && window.isShowing()) return;
        }

        retry.stop();
        handled = true;
        try {
            if (!markShown()) return;
        } catch (IOException | RuntimeException ignored) {
            // Skip the invitation when its state cannot be saved.
            return;
        }

        var options = new String[]{"去 GitHub Star", "不了，谢谢"};
        int choice = JOptionPane.showOptionDialog(owner,
                "如果 NCM Breaker 对你有帮助，\n欢迎在 GitHub 给项目一个 Star，帮助更多人发现它。",
                "支持 NCM Breaker", JOptionPane.YES_NO_OPTION, JOptionPane.INFORMATION_MESSAGE,
                null, options, options[1]);
        if (choice == 0) openProject();
    }

    private boolean markShown() throws IOException {
        var directory = ApplicationPaths.directory().resolve("config");
        Files.createDirectories(directory);
        try {
            // Creating the marker once also prevents duplicate invitations across app instances.
            Files.writeString(directory.resolve("star-invitation.json"), new Gson().toJson(Map.of("shown", true)) + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            return true;
        } catch (FileAlreadyExistsException ignored) {
            return false;
        }
    }

    private void openProject() {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(PROJECT_URI);
                return;
            }
        } catch (IOException | UnsupportedOperationException | SecurityException ignored) {
            // Keep the project address available when the browser cannot be opened.
        }
        JOptionPane.showMessageDialog(owner, "请在浏览器中打开：\n" + PROJECT_URI,
                "打开 GitHub", JOptionPane.INFORMATION_MESSAGE);
    }

    @Override public void close() {
        closed = true;
        retry.stop();
    }
}
