package com.ncmbreaker.ui.account;

import com.ncmbreaker.netease.auth.LoginSession;
import com.ncmbreaker.ui.UiStyle;
import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.function.Consumer;

public final class AccountMenu extends JButton implements AutoCloseable {
    private final AccountPanel panel = new AccountPanel();
    private LoginSession session;
    private JDialog dialog;
    private Consumer<LoginSession> sessionListener = value -> { };
    private boolean closed;

    public AccountMenu() {
        super("登录账号");
        UiStyle.button(this);
        setIcon(new javax.swing.plaf.metal.MetalComboBoxIcon());
        setHorizontalTextPosition(SwingConstants.LEFT);
        setIconTextGap(12);
        setFont(getFont().deriveFont(14f));
        setPreferredSize(new Dimension(188, 40));
        setName("account.menu");
        setToolTipText("登录网易云账号");
        panel.setSessionListener(this::sessionChanged);
        addActionListener(event -> showAccount());
    }

    public void setSessionListener(Consumer<LoginSession> listener) { sessionListener = listener; }
    public void restoreLogin() { panel.restoreLogin(); }

    private void sessionChanged(LoginSession value) {
        session = value;
        var account = value == null ? null : value.account();
        setText(account == null ? "登录账号" : account.nickname());
        setToolTipText(account == null ? "登录网易云账号" : panel.hasSaveWarning()
                ? "登录状态未保存，下次启动需要重新扫码" : account.nickname() + " · " + account.userId());
        sessionListener.accept(value);
        if (account != null && dialog != null) dialog.setVisible(false);
    }

    private void showMenu() {
        var popup = new JPopupMenu();
        var account = session.account();
        var identity = new JPanel(new BorderLayout(0, 6));
        identity.setOpaque(false);
        identity.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        var name = new JLabel(account.nickname());
        name.putClientProperty("html.disable", Boolean.TRUE);
        var id = new JLabel("账号 ID：" + account.userId());
        id.setForeground(UiStyle.muted());
        identity.add(name, BorderLayout.NORTH);
        identity.add(id, BorderLayout.CENTER);
        if (panel.hasSaveWarning()) {
            var warning = new JLabel("登录状态未保存，下次启动需要重新扫码");
            warning.setForeground(UIManager.getColor("nimbusAlertYellow"));
            identity.add(warning, BorderLayout.SOUTH);
        }
        popup.add(identity);
        popup.addSeparator();
        var logout = new JMenuItem("退出登录");
        logout.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        logout.addActionListener(event -> panel.logout());
        popup.add(logout);
        popup.show(this, getWidth() - popup.getPreferredSize().width, getHeight() + 4);
    }

    public void showAccount() {
        if (closed) return;
        if (session != null) {
            showMenu();
            return;
        }
        if (dialog == null) {
            dialog = new JDialog(SwingUtilities.getWindowAncestor(this), "网易云账号", Dialog.ModalityType.MODELESS);
            dialog.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
            dialog.setContentPane(panel);
            dialog.setResizable(false);
            dialog.getRootPane().registerKeyboardAction(event -> hideAccount(),
                    KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
            dialog.addWindowListener(new WindowAdapter() {
                @Override public void windowClosing(WindowEvent event) { panel.deactivate(); }
            });
        }
        panel.activate();
        dialog.pack();
        dialog.setLocationRelativeTo(SwingUtilities.getWindowAncestor(this));
        dialog.setVisible(true);
        dialog.toFront();
    }

    private void hideAccount() {
        panel.deactivate();
        dialog.setVisible(false);
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        panel.close();
        if (dialog != null) dialog.dispose();
    }
}
