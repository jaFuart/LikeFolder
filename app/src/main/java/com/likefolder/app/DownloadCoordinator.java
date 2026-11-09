package com.likefolder.app;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One download job across foreground services and WorkManager, with cancellable I/O. */
final class DownloadCoordinator {
    private static final Object LOCK = new Object();
    private static final ThreadLocal<Job> CURRENT = new ThreadLocal<>();
    private static final ExecutorService DISCONNECT = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "likefolder-disconnect");
        thread.setDaemon(true);
        return thread;
    });
    private static Job active;

    static final class Snapshot {
        final boolean running, stopping;
        final int done, total;
        final String currentTitle;
        Snapshot(boolean running, boolean stopping, int done, int total, String currentTitle) {
            this.running = running; this.stopping = stopping;
            this.done = done; this.total = total; this.currentTitle = currentTitle;
        }
    }

    static final class CancelledException extends InterruptedIOException {
        CancelledException() { super("Загрузка остановлена"); }
    }

    interface Commit { void run() throws IOException; }

    static Job tryStart() {
        synchronized (LOCK) {
            if (active != null) return null;
            active = new Job();
            return active;
        }
    }

    static Snapshot snapshot() {
        Job job;
        synchronized (LOCK) { job = active; }
        return job == null ? new Snapshot(false, false, 0, 0, "") : job.snapshot();
    }

    static void cancelActive() {
        Job job;
        synchronized (LOCK) { job = active; }
        if (job != null) job.cancel();
    }

    static void check() throws CancelledException {
        Job job = CURRENT.get();
        if (Thread.currentThread().isInterrupted() || (job != null && job.isCancelled()))
            throw new CancelledException();
    }

    static void register(HttpURLConnection connection) throws CancelledException {
        check();
        Job job = CURRENT.get();
        if (job != null) job.register(connection);
    }

    static void release(HttpURLConnection connection) {
        Job job = CURRENT.get();
        if (job != null) job.release(connection);
    }

    /** Publishing a finished file and recording it are one cancellation boundary. */
    static void commit(Commit commit) throws IOException {
        Job job = CURRENT.get();
        if (job == null) { check(); commit.run(); }
        else job.commit(commit);
    }

    static final class Job implements AutoCloseable {
        private final Set<HttpURLConnection> connections = new HashSet<>();
        private volatile boolean cancelled, closed;
        private Thread owner;
        private int done, total;
        private String currentTitle = "Подготовка загрузки…";

        void bind() throws CancelledException {
            synchronized (this) { owner = Thread.currentThread(); }
            CURRENT.set(this);
            check();
        }

        boolean isCancelled() { return cancelled; }

        synchronized void progress(int completed, int count, String title) {
            if (closed || cancelled) return;
            done = completed; total = count; currentTitle = title;
        }

        private synchronized Snapshot snapshot() {
            return new Snapshot(!closed, cancelled && !closed, done, total, currentTitle);
        }

        private void register(HttpURLConnection connection) throws CancelledException {
            synchronized (this) {
                if (!cancelled && !closed) { connections.add(connection); return; }
            }
            try { connection.disconnect(); } catch (RuntimeException ignored) { }
            throw new CancelledException();
        }

        private synchronized void release(HttpURLConnection connection) { connections.remove(connection); }

        private synchronized void commit(Commit commit) throws IOException {
            check();
            commit.run();
        }

        void cancel() {
            ArrayList<HttpURLConnection> pending;
            synchronized (this) {
                if (cancelled || closed) return;
                cancelled = true;
                pending = new ArrayList<>(connections);
                // Interrupt before close() can release this slot and the executor can reuse its thread.
                if (owner != null) owner.interrupt();
            }
            // Disconnect can block in a platform HTTP implementation. Keep the stop button responsive.
            for (HttpURLConnection connection : pending) DISCONNECT.execute(() -> {
                try { connection.disconnect(); } catch (RuntimeException ignored) { }
            });
        }

        @Override public void close() {
            ArrayList<HttpURLConnection> pending;
            synchronized (this) {
                if (closed) return;
                closed = true; owner = null;
                pending = new ArrayList<>(connections); connections.clear();
            }
            if (CURRENT.get() == this) CURRENT.remove();
            for (HttpURLConnection connection : pending) {
                try { connection.disconnect(); } catch (RuntimeException ignored) { }
            }
            synchronized (LOCK) { if (active == this) active = null; }
        }
    }

    private DownloadCoordinator() { }
}
