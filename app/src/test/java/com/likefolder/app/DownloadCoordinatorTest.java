package com.likefolder.app;

import org.junit.After;
import org.junit.Test;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public final class DownloadCoordinatorTest {
    private DownloadCoordinator.Job job;

    @After public void cleanup() {
        if (job != null) { job.cancel(); job.close(); }
        Thread.interrupted();
    }

    @Test public void noNewQueueStartsUntilCancelledJobFinishesCleanup() throws Exception {
        job = DownloadCoordinator.tryStart();
        assertNotNull(job);
        assertNull(DownloadCoordinator.tryStart());
        job.cancel();
        assertTrue(DownloadCoordinator.snapshot().stopping);
        assertNull(DownloadCoordinator.tryStart());
        assertThrows(DownloadCoordinator.CancelledException.class, job::bind);
        job.close();
        Thread.interrupted();
        DownloadCoordinator.Job old = job;
        job = DownloadCoordinator.tryStart();
        assertNotNull(job);
        old.close();
        assertTrue(DownloadCoordinator.snapshot().running);
        assertNull(DownloadCoordinator.tryStart());
    }

    @Test public void stopDisconnectsAReadThatDoesNotRespondToThreadInterrupts() throws Exception {
        job = DownloadCoordinator.tryStart();
        FakeConnection connection = new FakeConnection();
        CountDownLatch registered = new CountDownLatch(1);
        AtomicReference<Throwable> result = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                job.bind();
                DownloadCoordinator.register(connection);
                registered.countDown();
                // Model a platform read that only exits when its connection is disconnected.
                boolean interrupted = false;
                while (true) {
                    try { connection.disconnected.await(); break; }
                    catch (InterruptedException ignored) { interrupted = true; }
                }
                if (interrupted) Thread.currentThread().interrupt();
                DownloadCoordinator.check();
                result.set(new AssertionError("Cancelled read must not proceed to writing"));
            } catch (Throwable ex) { result.set(ex); }
            finally { DownloadCoordinator.release(connection); job.close(); }
        });
        worker.start();
        try {
            assertTrue(registered.await(2, TimeUnit.SECONDS));
            DownloadCoordinator.cancelActive();
            assertTrue(connection.disconnected.await(2, TimeUnit.SECONDS));
            worker.join(2000);
            assertFalse(worker.isAlive());
            assertTrue(result.get() instanceof DownloadCoordinator.CancelledException);
            assertFalse(DownloadCoordinator.snapshot().running);
        } finally {
            connection.disconnect(); worker.join(2000);
        }
    }

    @Test public void cancelBeforePublishDoesNotRecordACompletedFile() throws Exception {
        job = DownloadCoordinator.tryStart(); job.bind();
        AtomicBoolean published = new AtomicBoolean(), recorded = new AtomicBoolean();
        job.cancel();
        assertThrows(DownloadCoordinator.CancelledException.class, () -> DownloadCoordinator.commit(() -> {
            published.set(true); recorded.set(true);
        }));
        assertFalse(published.get()); assertFalse(recorded.get());
    }

    @Test public void cancelDuringPublishCannotSplitPublicationFromHistory() throws Exception {
        job = DownloadCoordinator.tryStart();
        CountDownLatch publishing = new CountDownLatch(1), allowRecord = new CountDownLatch(1);
        CountDownLatch cancelling = new CountDownLatch(1);
        AtomicBoolean published = new AtomicBoolean(), recorded = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            try {
                job.bind();
                DownloadCoordinator.commit(() -> {
                    published.set(true); publishing.countDown();
                    try {
                        if (!allowRecord.await(2, TimeUnit.SECONDS)) throw new IOException("test timeout");
                    } catch (InterruptedException ex) { throw new IOException(ex); }
                    recorded.set(true);
                });
            } catch (Throwable ex) { failure.set(ex); }
        });
        Thread stopper = new Thread(() -> { cancelling.countDown(); job.cancel(); });
        writer.start();
        try {
            assertTrue(publishing.await(2, TimeUnit.SECONDS));
            stopper.start();
            assertTrue(cancelling.await(2, TimeUnit.SECONDS));
            allowRecord.countDown();
            writer.join(2000); stopper.join(2000);
            assertFalse(writer.isAlive()); assertFalse(stopper.isAlive());
            assertNull(failure.get());
            assertTrue(published.get()); assertTrue(recorded.get());
            assertTrue(job.isCancelled());
        } finally { allowRecord.countDown(); writer.join(2000); stopper.join(2000); }
    }

    @Test public void releasedConnectionsAreNotAbortedByALaterStop() throws Exception {
        job = DownloadCoordinator.tryStart(); job.bind();
        FakeConnection completed = new FakeConnection();
        DownloadCoordinator.register(completed);
        DownloadCoordinator.release(completed);
        job.cancel();
        assertEquals(1L, completed.disconnected.getCount());
    }

    private static final class FakeConnection extends HttpURLConnection {
        final CountDownLatch disconnected = new CountDownLatch(1);
        FakeConnection() throws Exception { super(new URL("https://example.invalid/audio")); }
        @Override public void disconnect() { disconnected.countDown(); }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() { }
    }
}
