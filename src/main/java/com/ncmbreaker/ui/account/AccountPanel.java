package com.ncmbreaker.ui.account;

import com.ncmbreaker.netease.auth.LoginSession;
import com.ncmbreaker.ui.UiStyle;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagLayout;
import java.awt.image.BufferedImage;

public final class AccountPanel extends JPanel implements AutoCloseable {
    static final int QR_SIZE = 240;

    private final JLabel qr = new JLabel("尚未登录", SwingConstants.CENTER);
    private final JLabel status = centered("尚未登录");
    private final JLabel accountName = centered(" ");
    private final JLabel accountId = centered(" ");
    private final JButton primary = new JButton("扫码登录");
    private final JButton secondary = new JButton("取消");
    private final LoginController controller = new LoginController(this);
    private boolean saveWarning;
    private java.util.function.Consumer<LoginSession> sessionListener = session -> { };

    public void setSessionListener(java.util.function.Consumer<LoginSession> listener) {
        sessionListener = java.util.Objects.requireNonNull(listener);
    }

    void sessionChanged(LoginSession session) {
        sessionListener.accept(session);
    }

    public AccountPanel() {
        super(new GridBagLayout());
        var content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setOpaque(false);
        content.setBorder(BorderFactory.createEmptyBorder(10, 24, 10, 24));
        var title = centered("网易云账号");
        title.setFont(title.getFont().deriveFont(Font.PLAIN, 20f));
        content.add(title);
        content.add(Box.createVerticalStrut(16));

        qr.setName("login.qr");
        qr.setOpaque(true);
        qr.setBackground(Color.WHITE);
        qr.setForeground(new Color(0x505050));
        qr.setPreferredSize(new Dimension(QR_SIZE, QR_SIZE));
        qr.setMinimumSize(new Dimension(QR_SIZE, QR_SIZE));
        qr.setMaximumSize(new Dimension(QR_SIZE, QR_SIZE));
        qr.setAlignmentX(Component.CENTER_ALIGNMENT);
        content.add(qr);
        content.add(Box.createVerticalStrut(12));
        status.setName("login.status");
        status.setPreferredSize(new Dimension(440, 38));
        status.setMinimumSize(new Dimension(300, 38));
        status.setMaximumSize(new Dimension(440, 38));
        content.add(status);
        accountName.setFont(accountName.getFont().deriveFont(Font.PLAIN, 16f));
        accountName.setPreferredSize(new Dimension(440, 26));
        accountName.setMinimumSize(new Dimension(300, 26));
        accountName.setMaximumSize(new Dimension(440, 26));
        accountId.setForeground(UIManager.getColor("TextField.inactiveForeground"));
        accountId.setPreferredSize(new Dimension(440, 24));
        accountId.setMinimumSize(new Dimension(300, 24));
        accountId.setMaximumSize(new Dimension(440, 24));
        content.add(accountName);
        content.add(accountId);
        content.add(Box.createVerticalStrut(8));
        var actions = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 0));
        actions.setOpaque(false);
        actions.setPreferredSize(new Dimension(440, 38));
        actions.setMinimumSize(new Dimension(300, 38));
        actions.setMaximumSize(new Dimension(440, 38));
        for (var button : new JButton[]{primary, secondary}) {
            UiStyle.button(button);
            button.setPreferredSize(new Dimension(132, 36));
            button.setFocusPainted(false);
            actions.add(button);
        }
        primary.setName("login.primary");
        secondary.setName("login.secondary");
        primary.addActionListener(event -> controller.primaryAction());
        secondary.addActionListener(event -> controller.cancelOrLogout());
        content.add(actions);
        add(content);
        showIdle();
    }

    public void activate() {
        controller.activate();
    }

    public void restoreLogin() {
        controller.initialize();
    }

    public void deactivate() { controller.deactivate(); }

    public void logout() { controller.cancelOrLogout(); }

    void showRestoring() {
        placeholder("正在恢复登录");
        status.setText("正在确认登录状态");
        accountName.setText(" ");
        accountId.setText(" ");
        actions("恢复中", false, false, "取消");
    }

    void showRestoreError(String message) {
        placeholder("暂时无法恢复登录");
        status.setText(message);
        status.setToolTipText(message);
        actions("重试", true, true, "重新扫码");
    }

    void showForgetError() {
        placeholder("尚未登录");
        accountName.setText(" ");
        accountId.setText(" ");
        status.setText("无法清除已保存的登录状态，请重试。");
        actions("重试", true, false, "取消");
    }

    void showSaveWarning() {
        saveWarning = true;
        status.setText("已登录，但登录状态未能保存。");
        status.setToolTipText("请检查程序所在目录的写入权限；下次启动需要重新扫码。");
    }

    boolean hasSaveWarning() { return saveWarning; }

    void showIdle() {
        placeholder("尚未登录");
        status.setText("尚未登录");
        accountName.setText(" ");
        accountId.setText(" ");
        actions("扫码登录", true, false, "取消");
    }

    void showLoading() {
        placeholder("正在获取二维码");
        status.setText("正在连接网易云音乐");
        accountName.setText(" ");
        accountId.setText(" ");
        actions("获取中", false, true, "取消");
    }

    void showQr(BufferedImage image) {
        qr.setText("");
        qr.setIcon(new ImageIcon(image));
        showWaiting();
        actions("刷新二维码", true, true, "取消");
    }

    void showWaiting() {
        status.setText("请使用网易云音乐扫码登录");
    }

    void showScanned() {
        status.setText("已扫码，请在手机上确认登录");
    }

    void showVerifying() {
        placeholder("正在验证账号");
        status.setText("正在确认登录状态");
        actions("验证中", false, true, "取消");
    }

    void showAccount(LoginSession.Account account) {
        saveWarning = false;
        placeholder("已登录");
        qr.setVisible(false);
        status.setText("登录成功");
        // Treat account names as plain text, including names beginning with HTML markup.
        accountName.putClientProperty("html.disable", Boolean.TRUE);
        accountName.setText(account.nickname());
        accountName.setToolTipText(account.nickname());
        accountId.setText("账号 ID：" + account.userId());
        actions("扫码登录", false, true, "退出登录");
        primary.setVisible(false);
    }

    void showExpired() {
        placeholder("二维码已过期");
        status.setText("请刷新二维码");
        actions("刷新二维码", true, false, "取消");
    }

    void showConnectionRetry() {
        status.setText("连接中断，正在重试");
    }

    void showError(String message, boolean authorized) {
        placeholder("连接失败");
        status.setText(message);
        status.setToolTipText(message);
        actions(authorized ? "重新验证" : "重试", true, true, "取消");
    }

    private void placeholder(String text) {
        qr.setVisible(true);
        qr.setIcon(null);
        qr.setText(text);
        status.setToolTipText(null);
    }

    private void actions(String text, boolean enabled, boolean secondaryVisible, String secondaryText) {
        primary.setVisible(true);
        primary.setText(text);
        primary.setEnabled(enabled);
        secondary.setText(secondaryText);
        secondary.setVisible(secondaryVisible);
    }

    private static JLabel centered(String text) {
        var label = new JLabel(text, SwingConstants.CENTER);
        label.setAlignmentX(Component.CENTER_ALIGNMENT);
        return label;
    }

    @Override
    public void close() {
        controller.close();
    }
}
