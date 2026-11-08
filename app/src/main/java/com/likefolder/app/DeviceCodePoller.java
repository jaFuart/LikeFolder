package com.likefolder.app;

import java.io.IOException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Polls one device code until authorization, cancellation, or expiry. */
final class DeviceCodePoller {
    private static final long MIN_INTERVAL_MS = 5000;
    private static final long MAX_NETWORK_DELAY_MS = 60000;
    private static final Pattern OAUTH_ERROR = Pattern.compile(
            "\"error\"\\s*:\\s*\"(authorization_pending|slow_down)\"");

    interface Request<T> { T request() throws IOException; }
    interface Clock { long now(); }
    interface Sleeper { void sleep(long millis) throws InterruptedException; }
    interface Listener { void onWaiting(boolean networkRetry); }

    static final class ExpiredException extends IOException {
        ExpiredException(IOException networkFailure) {
            super(networkFailure == null
                    ? "Время действия кода истекло. Получи новый код."
                    : "Время действия кода истекло: нет связи с Яндексом. Проверь интернет, VPN и Частный DNS, затем получи новый код.",
                    networkFailure);
        }
    }

    static <T> T await(Request<T> request, long intervalMs, long expiresAtMs,
            Clock clock, Sleeper sleeper, Listener listener) throws IOException, InterruptedException {
        long serverInterval = Math.max(MIN_INTERVAL_MS, intervalMs);
        long delay = serverInterval;
        IOException lastNetworkFailure = null;
        while (true) {
            checkInterrupted();
            long remaining = expiresAtMs - clock.now();
            if (remaining <= 0) throw new ExpiredException(lastNetworkFailure);
            sleeper.sleep(Math.min(delay, remaining));
            checkInterrupted();
            // The code can expire while sleeping, particularly after a network backoff.
            if (clock.now() >= expiresAtMs) throw new ExpiredException(lastNetworkFailure);
            try {
                T result = request.request();
                checkInterrupted();
                return result;
            } catch (IOException ex) {
                checkInterrupted();
                if (ex instanceof Net.ApiError) {
                    Matcher error = OAUTH_ERROR.matcher(String.valueOf(ex.getMessage()));
                    if (!error.find()) throw ex;
                    lastNetworkFailure = null;
                    if ("slow_down".equals(error.group(1))) serverInterval += MIN_INTERVAL_MS;
                    delay = serverInterval;
                    listener.onWaiting(false);
                } else if (ex instanceof UnknownHostException || ex instanceof SocketTimeoutException
                        || ex instanceof SocketException) {
                    lastNetworkFailure = ex;
                    delay = Math.max(serverInterval, Math.min(MAX_NETWORK_DELAY_MS, delay * 2));
                    listener.onWaiting(true);
                } else {
                    // Malformed responses, TLS errors, and other terminal failures must surface.
                    throw ex;
                }
            }
        }
    }

    private static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Авторизация отменена");
    }

    private DeviceCodePoller() { }
}
