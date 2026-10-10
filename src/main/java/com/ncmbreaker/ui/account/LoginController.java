package com.ncmbreaker.ui.account;

import com.ncmbreaker.netease.auth.LoginSession;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

final class LoginController implements AutoCloseable {
    private record PreparedChallenge(LoginSession.Challenge challenge, BufferedImage image) {
    }

    private record VerifiedAccount(LoginSession.Account account, boolean remembered) { }

    private final AccountPanel view;
    private final Timer timer;
    private LoginSession session;
    private LoginSession.Challenge challenge;
    private SwingWorker<?, ?> worker;
    private Instant expiresAt;
    private long generation;
    private boolean closed;
    private boolean authorized;
    private boolean started;
    private boolean activated;
    private boolean restoring;
    private boolean forgetPending;
    private boolean loginAfterForget;
    private int consecutiveErrors;

    LoginController(AccountPanel view) {
        this.view = view;
        timer = new Timer(2500, event -> poll());
        timer.setInitialDelay(1000);
    }

    void activate() {
        activated = true;
        if (!started) initialize();
        else if (!closed && worker == null && session == null && !forgetPending) refresh();
    }

    void initialize() {
        if (started || closed) return;
        started = true;
        restore();
    }

    private void restore() {
        if (closed) return;
        endAttempt();
        restoring = true;
        session = new LoginSession();
        var currentSession = session;
        view.showRestoring();
        submit(() -> {
            if (!currentSession.restoreSaved()) return null;
            try {
                var account = currentSession.verifyAccount();
                return new VerifiedAccount(account, currentSession.remember());
            } catch (LoginSession.ExpiredException exception) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                LoginSession.forgetSaved();
                return null;
            }
        }, verified -> {
            restoring = false;
            if (verified == null) {
                endAttempt();
                if (activated) refresh();
                else view.showIdle();
            } else {
                authorized = true;
                showVerified(currentSession, verified);
            }
        });
    }

    void primaryAction() {
        if (closed || worker != null) return;
        if (forgetPending) {
            forgetSaved(loginAfterForget);
        } else if (restoring) {
            restore();
        } else if (authorized && session != null && session.account() == null) {
            verifyAccount();
        } else {
            refresh();
        }
    }

    void refresh() {
        if (closed) {
            return;
        }
        endAttempt();
        session = new LoginSession();
        var currentSession = session;
        view.showLoading();
        submit(() -> {
            var result = currentSession.createChallenge();
            return new PreparedChallenge(result, QrCodeImage.create(result.url(), AccountPanel.QR_SIZE));
        }, prepared -> {
            challenge = prepared.challenge();
            expiresAt = Instant.now().plusSeconds(300);
            view.showQr(prepared.image());
            timer.start();
        });
    }

    void cancelOrLogout() {
        boolean requestQr = restoring;
        endAttempt();
        forgetSaved(requestQr);
    }

    private void forgetSaved(boolean requestQr) {
        forgetPending = true;
        loginAfterForget = requestQr;
        try {
            LoginSession.forgetSaved();
            forgetPending = false;
            if (requestQr) refresh();
            else view.showIdle();
        } catch (IOException exception) {
            view.showForgetError();
        }
    }

    private void poll() {
        if (closed || worker != null || challenge == null || session == null) {
            return;
        }
        if (Instant.now().isAfter(expiresAt)) {
            endAttempt();
            view.showExpired();
            return;
        }
        var currentSession = session;
        var currentChallenge = challenge;
        submit(() -> currentSession.check(currentChallenge), result -> {
            consecutiveErrors = 0;
            switch (result) {
                case EXPIRED -> {
                    endAttempt();
                    view.showExpired();
                }
                case WAITING -> view.showWaiting();
                case SCANNED -> view.showScanned();
                case AUTHORIZED -> {
                    authorized = true;
                    timer.stop();
                    verifyAccount();
                }
            }
        });
    }

    private void verifyAccount() {
        if (closed || worker != null || session == null) {
            return;
        }
        view.showVerifying();
        var currentSession = session;
        submit(() -> {
            var account = currentSession.verifyAccount();
            return new VerifiedAccount(account, currentSession.remember());
        }, verified -> showVerified(currentSession, verified));
    }

    private void showVerified(LoginSession currentSession, VerifiedAccount verified) {
        view.showAccount(verified.account());
        if (!verified.remembered()) view.showSaveWarning();
        view.sessionChanged(currentSession);
    }

    private <T> void submit(Callable<T> operation, Consumer<T> onSuccess) {
        var requestGeneration = generation;
        worker = new SwingWorker<T, Void>() {
            @Override
            protected T doInBackground() throws Exception {
                return operation.call();
            }

            @Override
            protected void done() {
                if (closed || requestGeneration != generation) {
                    return;
                }
                worker = null;
                try {
                    onSuccess.accept(get());
                } catch (CancellationException ignored) {
                    // A cancelled attempt cannot update the next QR code or session.
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException exception) {
                    failed(exception.getCause());
                }
            }
        };
        worker.execute();
    }

    private void failed(Throwable cause) {
        if (timer.isRunning() && ++consecutiveErrors < 3) {
            view.showConnectionRetry();
            return;
        }
        timer.stop();
        if (restoring) {
            var message = cause instanceof HttpTimeoutException ? "连接超时，请重试。"
                    : cause instanceof IOException && cause.getMessage() != null
                    && cause.getMessage().startsWith("保存的登录状态") ? cause.getMessage()
                    : "暂时无法恢复登录，请检查网络后重试。";
            view.showRestoreError(message);
            return;
        }
        var message = cause instanceof HttpTimeoutException ? "连接超时，请重试。"
                : cause instanceof IOException && cause.getMessage() != null
                && (cause.getMessage().startsWith("服务器") || cause.getMessage().startsWith("请求失败")
                || cause.getMessage().startsWith("二维码") || cause.getMessage().startsWith("账号")
                || cause.getMessage().startsWith("登录") || cause.getMessage().startsWith("暂时"))
                ? cause.getMessage() : "连接失败，请检查网络后重试。";
        view.showError(message, authorized);
    }

    private void endAttempt() {
        generation++;
        timer.stop();
        if (worker != null) {
            worker.cancel(true);
            worker = null;
        }
        if (session != null) {
            view.sessionChanged(null);
            session.close();
            session = null;
        }
        challenge = null;
        authorized = false;
        restoring = false;
        consecutiveErrors = 0;
    }

    @Override
    public void close() {
        closed = true;
        endAttempt();
    }
}
