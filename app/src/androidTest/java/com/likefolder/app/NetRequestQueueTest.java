package com.likefolder.app;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Exercises real Net/JSON/cancellation wiring with fake connections; never opens a socket. */
@RunWith(AndroidJUnit4.class)
public final class NetRequestQueueTest {
    private static final String MUSIC = "https://api.music.yandex.net/tracks";
    private static final String OAUTH = "https://oauth.yandex.ru/token";

    @Test public void retryClosesResponseBeforeReplayAndStaysOnCallerThread() throws Exception {
        AtomicLong now = new AtomicLong(1000);
        List<Long> sleeps = new ArrayList<>();
        ApiRequestQueue queue = new ApiRequestQueue(now::get, millis -> {
            sleeps.add(millis);
            now.addAndGet(millis);
        });
        Thread caller = Thread.currentThread();
        FakeConnection limited = new FakeConnection(MUSIC, 429, "{\"error\":\"limit\"}", "2");
        FakeConnection success = new FakeConnection(MUSIC, 200, "{\"result\":true}", null);
        AtomicInteger opens = new AtomicInteger();
        AtomicInteger callbacks = new AtomicInteger();
        try (ApiRequestQueue.ListenerScope ignored = ApiRequestQueue.withListener((delay, attempt) -> {
            assertSame(caller, Thread.currentThread());
            assertTrue(limited.streamClosed.get());
            assertEquals(1, limited.disconnects.get());
            assertEquals(2000L, delay);
            assertEquals(1, attempt);
            callbacks.incrementAndGet();
        })) {
            JSONObject result = Net.json(MUSIC, "POST", "track-ids=1001", "synthetic-token", queue, url -> {
                assertSame(caller, Thread.currentThread());
                if (opens.getAndIncrement() == 0) return limited;
                assertEquals("The previous connection must be closed before reopening", 1, limited.disconnects.get());
                return success;
            });
            assertTrue(result.getBoolean("result"));
        }
        assertEquals(2, opens.get());
        assertEquals(1, callbacks.get());
        assertEquals(java.util.Collections.singletonList(2000L), sleeps);
        for (FakeConnection connection : new FakeConnection[]{limited, success}) {
            assertSame(caller, connection.requestThread);
            assertEquals("POST", connection.getRequestMethod());
            assertEquals("OAuth synthetic-token", connection.getRequestProperty("Authorization"));
            assertEquals("track-ids=1001", new String(connection.requestBody.toByteArray(), StandardCharsets.UTF_8));
            assertTrue(connection.streamClosed.get());
            assertEquals(1, connection.disconnects.get());
        }
    }

    @Test public void oauthRequestCompletesWhileMusicQueueIsOccupied() throws Exception {
        AtomicLong now = new AtomicLong(1000);
        ApiRequestQueue queue = new ApiRequestQueue(now::get, now::addAndGet);
        CountDownLatch musicEntered = new CountDownLatch(1), releaseMusic = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = executor.submit(() -> queue.execute(() -> {
                musicEntered.countDown();
                awaitLatch(releaseMusic);
                return null;
            }));
            assertTrue(musicEntered.await(5, TimeUnit.SECONDS));
            FakeConnection oauth = new FakeConnection(OAUTH, 200, "{\"access_token\":\"fake\"}", null);
            Future<JSONObject> request = executor.submit(() -> Net.json(OAUTH, "POST", "grant_type=device_code",
                    null, queue, url -> oauth));
            assertEquals("fake", request.get(3, TimeUnit.SECONDS).getString("access_token"));
            assertFalse("OAuth must finish before the Music queue is released", holder.isDone());
            assertNull(oauth.getRequestProperty("Authorization"));
            assertEquals(1, oauth.disconnects.get());
            releaseMusic.countDown();
            holder.get(5, TimeUnit.SECONDS);
        } finally {
            releaseMusic.countDown();
            shutdown(executor);
        }
    }

    @Test public void musicCallsSharePermitAndQueuedDownloadCanBeCancelled() throws Exception {
        AtomicLong now = new AtomicLong(1000);
        ApiRequestQueue queue = new ApiRequestQueue(now::get, now::addAndGet);
        AtomicInteger active = new AtomicInteger(), maximum = new AtomicInteger(), opens = new AtomicInteger();
        CountDownLatch firstEntered = new CountDownLatch(1), releaseFirst = new CountDownLatch(1);
        CountDownLatch jobStarted = new CountDownLatch(1);
        AtomicReference<Thread> jobThread = new AtomicReference<>();
        DownloadCoordinator.Job job = DownloadCoordinator.tryStart();
        assertNotNull("Tests must not overlap real download jobs", job);
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            FakeConnection first = new FakeConnection(MUSIC, 200, "{\"result\":1}", null);
            first.active = active; first.maximum = maximum;
            first.entered = firstEntered; first.release = releaseFirst;
            Future<JSONObject> library = executor.submit(() -> Net.json(MUSIC, "GET", null, null, queue, url -> {
                opens.incrementAndGet();
                return first;
            }));
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
            Future<IOException> cancelled = executor.submit(() -> {
                job.bind();
                jobThread.set(Thread.currentThread());
                jobStarted.countDown();
                try {
                    Net.json(MUSIC, "GET", null, null, queue, url -> {
                        fail("A cancelled queued download must never create a connection");
                        return null;
                    });
                    throw new AssertionError("Queued download should be cancelled");
                } catch (IOException ex) {
                    assertTrue(Thread.currentThread().isInterrupted());
                    return ex;
                } finally { job.close(); }
            });
            assertTrue(jobStarted.await(5, TimeUnit.SECONDS));
            awaitWaiting(jobThread.get());
            FakeConnection second = new FakeConnection(MUSIC, 200, "{\"result\":2}", null);
            second.active = active; second.maximum = maximum;
            Future<JSONObject> next = executor.submit(() -> Net.json(MUSIC, "GET", null, null, queue, url -> {
                opens.incrementAndGet();
                assertEquals("The earlier request must disconnect before the next opens", 1, first.disconnects.get());
                return second;
            }));
            job.cancel();
            assertTrue(cancelled.get(3, TimeUnit.SECONDS) instanceof InterruptedIOException);
            assertFalse(DownloadCoordinator.snapshot().running);
            assertEquals(1, opens.get());
            assertEquals("Cancelling a waiter must not release another request's permit", 1, active.get());
            assertFalse(library.isDone());
            releaseFirst.countDown();
            assertEquals(1, library.get(5, TimeUnit.SECONDS).getInt("result"));
            assertEquals(2, next.get(5, TimeUnit.SECONDS).getInt("result"));
            assertEquals(2, opens.get());
            assertEquals(1, maximum.get());
            assertEquals(0, active.get());
        } finally {
            releaseFirst.countDown();
            job.cancel();
            shutdown(executor);
            job.close();
        }
    }

    @Test public void cancellingDownloadDuringBackoffReleasesPermitAndConnection() throws Exception {
        AtomicLong now = new AtomicLong(1000);
        CountDownLatch backoffStarted = new CountDownLatch(1);
        AtomicBoolean blockFirstSleep = new AtomicBoolean(true);
        ApiRequestQueue queue = new ApiRequestQueue(now::get, millis -> {
            if (blockFirstSleep.getAndSet(false)) {
                backoffStarted.countDown();
                new CountDownLatch(1).await(); // Only cancellation may end this synthetic backoff.
            } else now.addAndGet(millis);
        });
        FakeConnection limited = new FakeConnection(MUSIC, 429, "{\"error\":\"limit\"}", "5");
        DownloadCoordinator.Job job = DownloadCoordinator.tryStart();
        assertNotNull(job);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<IOException> cancelled = executor.submit(() -> {
                job.bind();
                try {
                    Net.json(MUSIC, "GET", null, null, queue, url -> limited);
                    throw new AssertionError("Backoff should be cancelled");
                } catch (IOException ex) {
                    assertTrue(Thread.currentThread().isInterrupted());
                    return ex;
                } finally { job.close(); }
            });
            assertTrue(backoffStarted.await(5, TimeUnit.SECONDS));
            assertTrue(limited.streamClosed.get());
            assertEquals(1, limited.disconnects.get());
            job.cancel();
            assertTrue(cancelled.get(3, TimeUnit.SECONDS) instanceof InterruptedIOException);
            assertFalse(DownloadCoordinator.snapshot().running);
            assertEquals("The finished HTTP connection must no longer be registered with the job", 1, limited.disconnects.get());
            now.set(10_000); // Advance past the shared server cooldown without real-time sleeps.
            FakeConnection success = new FakeConnection(MUSIC, 200, "{\"result\":true}", null);
            assertTrue(Net.json(MUSIC, "GET", null, null, queue, url -> success).getBoolean("result"));
            assertEquals(1, success.disconnects.get());
        } finally {
            job.cancel();
            shutdown(executor);
            job.close();
        }
    }

    private static void awaitWaiting(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Thread.State state = thread.getState();
            if (state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING) return;
            Thread.sleep(10);
        }
        fail("Download thread did not start waiting for the occupied API queue");
    }

    private static void awaitLatch(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IOException("Timed out waiting for test fixture");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Synthetic connection interrupted");
        }
    }

    private static void shutdown(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        assertTrue("Synthetic request threads must stop", executor.awaitTermination(5, TimeUnit.SECONDS));
    }

    private static final class FakeConnection extends HttpURLConnection {
        private final int responseStatus;
        private final String retryAfter;
        private final InputStream responseBody;
        final ByteArrayOutputStream requestBody = new ByteArrayOutputStream();
        final AtomicBoolean streamClosed = new AtomicBoolean();
        final AtomicInteger disconnects = new AtomicInteger();
        private final AtomicBoolean countedActive = new AtomicBoolean();
        volatile Thread requestThread;
        AtomicInteger active, maximum;
        CountDownLatch entered, release;

        FakeConnection(String url, int responseStatus, String responseBody, String retryAfter) throws Exception {
            super(new URL(url));
            this.responseStatus = responseStatus;
            this.retryAfter = retryAfter;
            this.responseBody = new ByteArrayInputStream(responseBody.getBytes(StandardCharsets.UTF_8)) {
                @Override public void close() throws IOException {
                    streamClosed.set(true);
                    super.close();
                }
            };
        }

        @Override public int getResponseCode() throws IOException {
            requestThread = Thread.currentThread();
            if (active != null && countedActive.compareAndSet(false, true))
                maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
            if (entered != null) entered.countDown();
            if (release != null) awaitLatch(release);
            return responseStatus;
        }
        @Override public String getHeaderField(String name) {
            return "Retry-After".equalsIgnoreCase(name) ? retryAfter : null;
        }
        @Override public InputStream getErrorStream() { return responseBody; }
        @Override public InputStream getInputStream() { return responseBody; }
        @Override public OutputStream getOutputStream() { return requestBody; }
        @Override public void disconnect() {
            disconnects.incrementAndGet();
            if (active != null && countedActive.compareAndSet(true, false)) active.decrementAndGet();
        }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() { }
    }
}
