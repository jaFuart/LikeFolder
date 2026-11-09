package com.likefolder.app;

import org.junit.Test;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public final class ApiRequestQueueTest {
    @Test public void requestGapStartsAfterThePreviousResponseFinishes() throws Exception {
        Time time = new Time(); ApiRequestQueue queue = time.queue();
        queue.execute(() -> { time.now.addAndGet(1_750); return null; });
        assertEquals(Long.valueOf(2_250), queue.execute(time.now::get));
        assertEquals(Arrays.asList(500L), time.sleeps);
    }

    @Test public void concurrentRequestsRunInArrivalOrderWithoutOverlap() throws Exception {
        Time time = new Time(); ApiRequestQueue queue = time.queue();
        List<Integer> order = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger active = new AtomicInteger(), maximum = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch firstRunning = new CountDownLatch(1), finishFirst = new CountDownLatch(1);
        Thread first = thread(() -> queue.execute(() -> {
            maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
            order.add(1); firstRunning.countDown(); await(finishFirst); active.decrementAndGet(); return null;
        }), failure);
        Thread second = thread(() -> queue.execute(() -> {
            maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
            order.add(2); active.decrementAndGet(); return null;
        }), failure);
        Thread third = thread(() -> queue.execute(() -> {
            maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
            order.add(3); active.decrementAndGet(); return null;
        }), failure);
        first.start();
        try {
            assertTrue(firstRunning.await(2, TimeUnit.SECONDS));
            second.start(); waitUntilQueued(second);
            third.start(); waitUntilQueued(third);
            finishFirst.countDown();
            join(first); join(second); join(third);
            assertNull(failure.get()); assertEquals(1, maximum.get());
            assertEquals(Arrays.asList(1, 2, 3), order);
            assertEquals(Arrays.asList(500L, 500L), time.sleeps);
        } finally { finishFirst.countDown(); stop(first, second, third); }
    }

    @Test public void retries429WithExponentialBackoffThenReturnsSuccess() throws Exception {
        Time time = new Time(); ApiRequestQueue queue = time.queue(); AtomicInteger calls = new AtomicInteger();
        List<String> retries = new ArrayList<>();
        try (ApiRequestQueue.ListenerScope ignored = ApiRequestQueue.withListener((delay, attempt) -> retries.add(attempt + ":" + delay))) {
            assertEquals("ok", queue.execute(() -> {
                if (calls.incrementAndGet() < 3) throw limit(0);
                return "ok";
            }));
        }
        assertEquals(3, calls.get()); assertEquals(Arrays.asList(1_000L, 2_000L), time.sleeps);
        assertEquals(Arrays.asList("1:1000", "2:2000"), retries);
    }

    @Test public void authenticationAndTransportFailuresAreNeverRetried() {
        for (IOException failure : new IOException[]{
                new ApiRequestQueue.HttpError(401, "expired", 0),
                new ApiRequestQueue.HttpError(403, "denied", 0), new IOException("offline")}) {
            Time time = new Time(); AtomicInteger calls = new AtomicInteger();
            IOException actual = assertThrows(IOException.class, () -> time.queue().execute(() -> {
                calls.incrementAndGet(); throw failure;
            }));
            assertSame(failure, actual); assertEquals(1, calls.get()); assertTrue(time.sleeps.isEmpty());
        }
    }

    @Test public void retryAfterSupportsSecondsHttpDatesAndMalformedValues() {
        assertEquals(12_000, ApiRequestQueue.retryAfterMillis(" 12 ", 0));
        assertEquals(3_000, ApiRequestQueue.retryAfterMillis("Thu, 1 Jan 1970 00:00:05 GMT", 2_000));
        assertEquals(0, ApiRequestQueue.retryAfterMillis("Thu, 1 Jan 1970 00:00:01 GMT", 2_000));
        for (String value : new String[]{null, "", "bad-date", "-1", "1.5"})
            assertEquals(0, ApiRequestQueue.retryAfterMillis(value, 0));
        assertEquals(Long.MAX_VALUE, ApiRequestQueue.retryAfterMillis("9223372036854775807", 0));
        assertEquals(Long.MAX_VALUE, ApiRequestQueue.retryAfterMillis("999999999999999999999999999999", 0));
    }

    @Test public void serverPauseIsNeverShortenedToLocalBackoff() throws Exception {
        Time time = new Time(); AtomicInteger calls = new AtomicInteger();
        long completedAt = time.queue().execute(() -> {
            if (calls.incrementAndGet() == 1) throw limit(7_000);
            return time.now.get();
        });
        assertEquals(7_000, completedAt); assertEquals(Arrays.asList(7_000L), time.sleeps);
    }

    @Test public void longServerPauseFailsPromptlyAndBlocksLaterRequestsUntilItsDeadline() throws Exception {
        Time time = new Time(); ApiRequestQueue queue = time.queue(); AtomicInteger calls = new AtomicInteger();
        ApiRequestQueue.RateLimitException first = assertThrows(ApiRequestQueue.RateLimitException.class,
                () -> queue.execute(() -> { calls.incrementAndGet(); throw limit(120_000); }));
        assertEquals(120_000, first.retryAfterMillis); assertEquals(1, calls.get()); assertTrue(time.sleeps.isEmpty());
        assertThrows(ApiRequestQueue.RateLimitException.class, () -> queue.execute(calls::incrementAndGet));
        assertEquals(1, calls.get());
        time.now.set(119_500);
        List<String> notices = new ArrayList<>();
        try (ApiRequestQueue.ListenerScope ignored = ApiRequestQueue.withListener((delay, attempt) -> notices.add(attempt + ":" + delay))) {
            assertEquals(Integer.valueOf(2), queue.execute(calls::incrementAndGet));
        }
        assertEquals(120_000, time.now.get()); assertEquals(Arrays.asList("0:500"), notices);
    }

    @Test public void exhaustionKeepsTheFinalBackoffForTheNextCaller() throws Exception {
        Time time = new Time(); ApiRequestQueue queue = time.queue(); AtomicInteger calls = new AtomicInteger();
        ApiRequestQueue.RateLimitException error = assertThrows(ApiRequestQueue.RateLimitException.class,
                () -> queue.execute(() -> { calls.incrementAndGet(); throw limit(0); }));
        assertEquals(5, calls.get()); assertEquals(16_000, error.retryAfterMillis);
        assertEquals(Arrays.asList(1_000L, 2_000L, 4_000L, 8_000L), time.sleeps);
        assertEquals(Long.valueOf(31_000), queue.execute(time.now::get));
        assertEquals(Long.valueOf(16_000), time.sleeps.get(4));
    }

    @Test public void interruptingQueuedCallerDoesNotRunItsRequestOrBlockSuccessor() throws Exception {
        Time time = new Time(); ApiRequestQueue queue = time.queue();
        CountDownLatch started = new CountDownLatch(1), finish = new CountDownLatch(1);
        AtomicReference<Throwable> firstFailure = new AtomicReference<>(), queuedFailure = new AtomicReference<>();
        AtomicBoolean ran = new AtomicBoolean(), interrupted = new AtomicBoolean();
        Thread first = thread(() -> queue.execute(() -> { started.countDown(); await(finish); return null; }), firstFailure);
        Thread queued = new Thread(() -> {
            try { queue.execute(() -> { ran.set(true); return null; }); }
            catch (Throwable ex) { queuedFailure.set(ex); interrupted.set(Thread.currentThread().isInterrupted()); }
        });
        first.start();
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS)); queued.start(); waitUntilQueued(queued);
            queued.interrupt(); join(queued);
            assertTrue(queuedFailure.get() instanceof InterruptedIOException); assertTrue(interrupted.get()); assertFalse(ran.get());
            finish.countDown(); join(first); assertNull(firstFailure.get());
            assertEquals("next", queue.execute(() -> "next"));
        } finally { finish.countDown(); stop(first, queued); }
    }

    @Test public void interruptingBackoffStopsRetryButPreservesSharedCooldown() throws Exception {
        AtomicLong now = new AtomicLong(); AtomicBoolean firstSleep = new AtomicBoolean(true);
        CountDownLatch sleeping = new CountDownLatch(1);
        ApiRequestQueue queue = new ApiRequestQueue(now::get, millis -> {
            if (firstSleep.getAndSet(false)) { sleeping.countDown(); new CountDownLatch(1).await(); }
            now.addAndGet(millis);
        });
        AtomicInteger calls = new AtomicInteger(); AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread retrying = thread(() -> queue.execute(() -> { calls.incrementAndGet(); throw limit(0); }), failure);
        retrying.start();
        try {
            assertTrue(sleeping.await(2, TimeUnit.SECONDS)); retrying.interrupt(); join(retrying);
            assertTrue(failure.get() instanceof InterruptedIOException); assertEquals(1, calls.get());
            assertEquals(Long.valueOf(1_000), queue.execute(now::get));
        } finally { stop(retrying); }
    }

    private static ApiRequestQueue.HttpError limit(long retryAfter) { return new ApiRequestQueue.HttpError(429, "limit", retryAfter); }
    private interface Task { void run() throws Exception; }
    private static Thread thread(Task task, AtomicReference<Throwable> failure) {
        return new Thread(() -> { try { task.run(); } catch (Throwable ex) { failure.set(ex); } });
    }
    private static void await(CountDownLatch latch) throws IOException {
        try { if (!latch.await(2, TimeUnit.SECONDS)) throw new IOException("test timeout"); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new InterruptedIOException(); }
    }
    private static void waitUntilQueued(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (thread.getState() != Thread.State.WAITING && System.nanoTime() < deadline) Thread.sleep(1);
        assertEquals(Thread.State.WAITING, thread.getState());
    }
    private static void join(Thread thread) throws InterruptedException { thread.join(2_000); assertFalse(thread.isAlive()); }
    private static void stop(Thread... threads) throws InterruptedException {
        for (Thread thread : threads) if (thread.isAlive()) thread.interrupt();
        for (Thread thread : threads) thread.join(2_000);
    }
    private static final class Time {
        final AtomicLong now = new AtomicLong();
        final List<Long> sleeps = Collections.synchronizedList(new ArrayList<>());
        ApiRequestQueue queue() { return new ApiRequestQueue(now::get, millis -> { sleeps.add(millis); now.addAndGet(millis); }); }
    }
}
