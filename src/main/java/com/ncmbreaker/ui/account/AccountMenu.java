package com.ncmbreaker.ui.account;

import com.ncmbreaker.netease.auth.LoginSession;
import com.ncmbreaker.netease.auth.MembershipService;
import com.ncmbreaker.ui.UiStyle;
import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

public final class AccountMenu extends JButton implements AutoCloseable {
    private final AccountPanel panel = new AccountPanel();
    private LoginSession session;
    private JDialog dialog;
    private JPopupMenu popup;
    private final JLabel membershipLabel = new JLabel();
    private final JLabel expirationLabel = new JLabel();
    private final JMenuItem refreshMembership = new JMenuItem("刷新会员信息");
    private MembershipService.Info membership = MembershipService.Info.unknown();
    private SwingWorker<MembershipService.Info, Void> membershipWorker;
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
        membershipLabel.setName("account.membership");
        expirationLabel.setForeground(UiStyle.muted());
        refreshMembership.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        refreshMembership.addActionListener(event -> loadMembership());
        panel.setSessionListener(this::sessionChanged);
        addActionListener(event -> showAccount());
    }

    public void setSessionListener(Consumer<LoginSession> listener) { sessionListener = listener; }
    public void restoreLogin() { panel.restoreLogin(); }

    private void sessionChanged(LoginSession value) {
        cancelMembership();
        if (popup != null) popup.setVisible(false);
        session = value;
        membership = MembershipService.Info.unknown();
        var account = value == null ? null : value.account();
        setText(account == null ? "登录账号" : account.nickname());
        updateMembership();
        sessionListener.accept(value);
        if (account != null && dialog != null) dialog.setVisible(false);
        if (account != null) loadMembership();
    }

    private void loadMembership() {
        if (closed || session == null || membershipWorker != null) return;
        var current = session;
        var account = current.account();
        if (account == null) return;
        membershipWorker = new SwingWorker<>() {
            @Override protected MembershipService.Info doInBackground() throws Exception {
                return MembershipService.fetch(current.client(), account.userId());
            }
            @Override protected void done() {
                if (closed || session != current || membershipWorker != this) return;
                membershipWorker = null;
                try { membership = get(); }
                catch (CancellationException ignored) { membership = MembershipService.Info.unknown(); }
                catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    membership = MembershipService.Info.unknown();
                } catch (Exception exception) { membership = MembershipService.Info.unknown(); }
                updateMembership();
            }
        };
        updateMembership();
        membershipWorker.execute();
    }

    private void updateMembership() {
        boolean loading = membershipWorker != null;
        var label = loading ? "正在读取会员信息" : membership.label();
        membershipLabel.setText(label);
        boolean showExpiration = !loading && membership.expiresAt() != null;
        expirationLabel.setVisible(showExpiration);
        expirationLabel.setText(showExpiration ? "有效期至 " + DateTimeFormatter.ofPattern("yyyy-MM-dd")
                .withZone(ZoneId.systemDefault()).format(membership.expiresAt()) : "");
        refreshMembership.setEnabled(!loading && session != null && !closed);
        var account = session == null ? null : session.account();
        setToolTipText(account == null ? "登录网易云账号" : panel.hasSaveWarning()
                ? "登录状态未保存，下次启动需要重新扫码" : account.nickname() + " · " + account.userId() + " · " + label);
        if (popup != null && popup.isVisible()) popup.pack();
    }

    private void cancelMembership() {
        if (membershipWorker != null) membershipWorker.cancel(true);
        membershipWorker = null;
    }

    private void showMenu() {
        popup = new JPopupMenu();
        var account = session.account();
        var identity = new JPanel();
        identity.setLayout(new BoxLayout(identity, BoxLayout.Y_AXIS));
        identity.setOpaque(false);
        identity.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        var name = new JLabel(account.nickname());
        name.putClientProperty("html.disable", Boolean.TRUE);
        var id = new JLabel("账号 ID：" + account.userId());
        id.setForeground(UiStyle.muted());
        name.setAlignmentX(Component.LEFT_ALIGNMENT);
        identity.add(name);
        for (var line : new JLabel[]{id, membershipLabel, expirationLabel}) {
            line.setAlignmentX(Component.LEFT_ALIGNMENT);
            line.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
            identity.add(line);
        }
        if (panel.hasSaveWarning()) {
            var warning = new JLabel("登录状态未保存，下次启动需要重新扫码");
            warning.setForeground(UIManager.getColor("nimbusAlertYellow"));
            warning.setAlignmentX(Component.LEFT_ALIGNMENT);
            warning.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
            identity.add(warning);
        }
        popup.add(identity);
        popup.addSeparator();
        popup.add(refreshMembership);
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
        cancelMembership();
        if (popup != null) popup.setVisible(false);
        panel.close();
        if (dialog != null) dialog.dispose();
    }
}
