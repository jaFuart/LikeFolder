package com.likefolder.app;

import org.junit.Test;

import java.io.IOException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.net.ssl.SSLHandshakeException;

import static org.junit.Assert.*;

public final class DeviceCodePollerTest {
    private static final class Time implements DeviceCodePoller.Clock, DeviceCodePoller.Sleeper {
        long now;
        final List<Long> sleeps = new ArrayList<>();
        @Override public long now() { return now; }
        @Override public void sleep(long millis) { sleeps.add(millis); now += millis; }
    }

    private static Net.ApiError oauthError(String code) {
        return new Net.ApiError(400, "HTTP 400 ({\"error\":\"" + code + "\"})");
    }

    @Test public void dnsFailureThenPendingThenSuccessKeepsPolling() throws Exception {
        Time time = new Time();
        List<Long> attempts = new ArrayList<>();
        List<Boolean> networkRetries = new ArrayList<>();
        String token = DeviceCodePoller.await(() -> {
            attempts.add(time.now);
            if (attempts.size() == 1) throw new UnknownHostException("oauth.yandex.ru");
            if (attempts.size() == 2) throw oauthError("authorization_pending");
            return "access-token";
        }, 5000, 300000, time, time, networkRetries::add);

        assertEquals("access-token", token);
        assertEquals(Arrays.asList(5000L, 15000L, 20000L), attempts);
        assertEquals(Arrays.asList(true, false), networkRetries);
    }

    @Test public void persistentDnsFailureExpiresWithoutRequestAfterDeadline() throws Exception {
        Time time = new Time();
        List<Long> attempts = new ArrayList<>();
        UnknownHostException failure = new UnknownHostException("oauth.yandex.ru");
        try {
            DeviceCodePoller.await(() -> {
                attempts.add(time.now);
                throw failure;
            }, 5000, 16000, time, time, retry -> { });
            fail("Expected expiry");
        } catch (DeviceCodePoller.ExpiredException expected) {
            assertSame(failure, expected.getCause());
        }
        assertEquals(Arrays.asList(5000L, 15000L), attempts);
        assertEquals(Arrays.asList(5000L, 10000L, 1000L), time.sleeps);
        assertEquals(16000L, time.now);
    }

    @Test public void slowDownPermanentlyIncreasesServerInterval() throws Exception {
        Time time = new Time();
        List<Long> attempts = new ArrayList<>();
        DeviceCodePoller.await(() -> {
            attempts.add(time.now);
            if (attempts.size() == 1) throw oauthError("slow_down");
            if (attempts.size() == 2) throw oauthError("authorization_pending");
            return "token";
        }, 5000, 300000, time, time, retry -> { });
        assertEquals(Arrays.asList(5000L, 15000L, 25000L), attempts);
    }

    @Test public void serverMinimumIsRespectedEvenAboveNetworkBackoffCap() throws Exception {
        Time time = new Time();
        int[] calls = {0};
        DeviceCodePoller.await(() -> {
            if (++calls[0] == 1) throw new SocketTimeoutException("timeout");
            return "token";
        }, 90000, 300000, time, time, retry -> { });
        assertEquals(Arrays.asList(90000L, 90000L), time.sleeps);
    }

    @Test public void connectionFailuresBackOffWithoutBusyLooping() throws Exception {
        Time time = new Time();
        int[] calls = {0};
        DeviceCodePoller.await(() -> {
            if (++calls[0] <= 5) throw new SocketException("connection reset");
            return "token";
        }, 5000, 300000, time, time, retry -> { });
        assertEquals(Arrays.asList(5000L, 10000L, 20000L, 40000L, 60000L, 60000L), time.sleeps);
    }

    @Test public void terminalOAuthAndResponseFailuresAreNotRetried() throws Exception {
        List<IOException> failures = Arrays.asList(
                oauthError("access_denied"), oauthError("invalid_grant"),
                new IOException("Invalid JSON"), new SSLHandshakeException("Bad certificate"),
                new Net.ApiError(400, "{\"error\":\"access_denied\",\"error_description\":\"authorization_pending\"}"));
        for (IOException failure : failures) {
            Time time = new Time();
            int[] calls = {0};
            try {
                DeviceCodePoller.await(() -> {
                    calls[0]++;
                    throw failure;
                }, 5000, 300000, time, time, retry -> fail("Must not retry"));
                fail("Expected terminal error");
            } catch (IOException expected) {
                assertSame(failure, expected);
            }
            assertEquals(1, calls[0]);
        }
    }

    @Test public void expiredCodeMakesNoNetworkRequest() throws Exception {
        Time time = new Time();
        time.now = 10000;
        try {
            DeviceCodePoller.await(() -> {
                fail("Expired code must not be polled");
                return "token";
            }, 5000, 10000, time, time, retry -> { });
            fail("Expected expiry");
        } catch (DeviceCodePoller.ExpiredException expected) {
            assertNull(expected.getCause());
        }
        assertTrue(time.sleeps.isEmpty());
    }

    @Test public void interruptionDuringWaitCancelsBeforeRequest() throws Exception {
        Time time = new Time();
        try {
            DeviceCodePoller.await(() -> {
                fail("Cancelled request must not start");
                return "token";
            }, 5000, 300000, time, millis -> {
                throw new InterruptedException("cancelled");
            }, retry -> { });
            fail("Expected cancellation");
        } catch (InterruptedException expected) {
            assertEquals("cancelled", expected.getMessage());
        }
    }

    @Test public void interruptedResponseCannotReturnToken() throws Exception {
        Time time = new Time();
        try {
            DeviceCodePoller.await(() -> {
                Thread.currentThread().interrupt();
                return "token";
            }, 5000, 300000, time, time, retry -> { });
            fail("Cancelled result must not be returned");
        } catch (InterruptedException expected) {
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }
}
