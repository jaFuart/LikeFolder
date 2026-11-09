package com.likefolder.app;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/** Serializes Music API calls across screens and downloads; OAuth and audio use separate paths. */
final class ApiRequestQueue {
    private static final long REQUEST_GAP_MILLIS = 500;
    private static final long MAX_WAIT_MILLIS = 60_000;
    private static final int MAX_RETRIES = 4;
    private static final ThreadLocal<Listener> LISTENER = new ThreadLocal<>();

    interface Request<T> { T run() throws IOException; }
    interface Clock { long nowMillis(); }
    interface Sleeper { void sleep(long millis) throws InterruptedException; }
    /** attempt is zero for a shared cooldown, or 1..4 for this request's retries. */
    interface Listener { void onRetry(long delayMillis, int attempt); }

    static class HttpError extends IOException {
        final int status;
        final long retryAfterMillis;
        HttpError(int status, String message, long retryAfterMillis) {
            super(message);
            this.status = status;
            this.retryAfterMillis = Math.max(0, retryAfterMillis);
        }
    }

    static final class RateLimitException extends HttpError {
        RateLimitException(long delayMillis, Throwable cause) {
            super(429, rateLimitMessage(delayMillis), delayMillis);
            if (cause != null) initCause(cause);
        }
    }

    /** Install on the thread performing API calls; close restores any enclosing listener. */
    static ListenerScope withListener(Listener listener) { return new ListenerScope(listener); }

    static final class ListenerScope implements AutoCloseable {
        private final Listener previous;
        private boolean closed;
        private ListenerScope(Listener listener) {
            previous = LISTENER.get();
            if (listener == null) LISTENER.remove(); else LISTENER.set(listener);
        }
        @Override public void close() {
            if (closed) return;
            closed = true;
            if (previous == null) LISTENER.remove(); else LISTENER.set(previous);
        }
    }

    private final ReentrantLock permit = new ReentrantLock(true);
    private final Clock clock;
    private final Sleeper sleeper;
    private long nextRequestAt, rateLimitedUntil;

    ApiRequestQueue() { this(new MonotonicClock(), Thread::sleep); }
    ApiRequestQueue(Clock clock, Sleeper sleeper) {
        this.clock = clock;
        this.sleeper = sleeper;
        nextRequestAt = rateLimitedUntil = clock.nowMillis();
    }

    <T> T execute(Request<T> request) throws IOException {
        try { permit.lockInterruptibly(); }
        catch (InterruptedException ex) { throw interrupted(ex); }
        try {
            int retry = 0;
            while (true) {
                awaitTurn(retry);
                try {
                    T result = request.run();
                    checkInterrupted();
                    return result;
                } catch (HttpError ex) {
                    if (ex.status != 429) throw ex;
                    // jsonOnce has closed and disconnected its response before this catch runs.
                    long backoff = 1_000L << retry;
                    long delay = Math.max(backoff, ex.retryAfterMillis);
                    long deadline = addClamped(clock.nowMillis(), delay);
                    rateLimitedUntil = Math.max(rateLimitedUntil, deadline);
                    nextRequestAt = Math.max(nextRequestAt, rateLimitedUntil);
                    if (delay > MAX_WAIT_MILLIS || retry == MAX_RETRIES)
                        throw new RateLimitException(remaining(nextRequestAt), ex);
                    ++retry;
                } finally {
                    // Enforce the gap after success, HTTP errors and transport failures alike.
                    nextRequestAt = Math.max(nextRequestAt, addClamped(clock.nowMillis(), REQUEST_GAP_MILLIS));
                }
            }
        } finally { permit.unlock(); }
    }

    private void awaitTurn(int retry) throws IOException {
        checkInterrupted();
        long delay = remaining(nextRequestAt);
        long limitedDelay = remaining(rateLimitedUntil);
        // Keep the shared deadline, but don't occupy a caller for minutes or hours.
        if (limitedDelay > MAX_WAIT_MILLIS) throw new RateLimitException(limitedDelay, null);
        Listener listener = LISTENER.get();
        if (limitedDelay > 0 && listener != null) listener.onRetry(delay, retry);
        while (delay > 0) {
            checkInterrupted();
            try { sleeper.sleep(delay); }
            catch (InterruptedException ex) { throw interrupted(ex); }
            checkInterrupted();
            delay = remaining(nextRequestAt);
        }
        checkInterrupted();
    }

    private long remaining(long deadline) { return Math.max(0, deadline - clock.nowMillis()); }

    /** Retry-After accepts either delta-seconds or an HTTP date; bad values use local backoff. */
    static long retryAfterMillis(String value, long nowEpochMillis) {
        if (value == null) return 0;
        String trimmed = value.trim();
        if (trimmed.matches("[0-9]+")) {
            try {
                long seconds = Long.parseLong(trimmed);
                return seconds > Long.MAX_VALUE / 1_000 ? Long.MAX_VALUE : seconds * 1_000;
            } catch (NumberFormatException ex) { return Long.MAX_VALUE; }
        }
        try {
            long target = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();
            return Math.max(0, target - nowEpochMillis);
        } catch (DateTimeParseException | ArithmeticException ex) { return 0; }
    }

    private static long addClamped(long value, long increment) {
        return value > Long.MAX_VALUE - increment ? Long.MAX_VALUE : value + increment;
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw interrupted(null);
    }

    private static InterruptedIOException interrupted(InterruptedException cause) {
        Thread.currentThread().interrupt();
        InterruptedIOException result = new InterruptedIOException("Ожидание запроса остановлено");
        if (cause != null) result.initCause(cause);
        return result;
    }

    private static String rateLimitMessage(long delayMillis) {
        String intro = "Яндекс Музыка временно ограничила запросы. ";
        if (delayMillis > 3_600_000) return intro + "Сервер просит длительную паузу. Попробуй позже.";
        long seconds = delayMillis / 1_000 + (delayMillis % 1_000 == 0 ? 0 : 1);
        return intro + "Повтори попытку примерно через " + Math.max(1, seconds) + " сек.";
    }

    private static final class MonotonicClock implements Clock {
        private final long origin = System.nanoTime();
        @Override public long nowMillis() { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - origin); }
    }
}
