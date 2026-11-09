package com.likefolder.app;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.work.WorkManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/** Session race regressions using the real shared preferences and Android Keystore, without network. */
@RunWith(AndroidJUnit4.class)
public final class StoreSessionTest {
    private Store first;
    private Store second;

    @Before public void prepareStores() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        WorkManager.getInstance(context).cancelAllWork().getResult().get(5, TimeUnit.SECONDS);
        first = new Store(context);
        second = new Store(context);
        first.signOut();
    }

    @After public void clearSyntheticCredentials() {
        if (first != null) first.signOut();
    }

    @Test public void staleRefreshCannotRestoreLogoutOrOverwriteAnotherLogin() {
        first.saveTokens("synthetic-access-a", "synthetic-refresh-a");
        Store.AuthSnapshot pending = second.authSnapshot();
        assertEquals("synthetic-refresh-a", second.getRefreshForAccess(pending));

        first.signOut();
        assertFalse(second.saveRefreshedTokens(pending, "synthetic-late-access", "synthetic-late-refresh"));
        assertFalse(first.isLoggedIn());
        assertEquals("", second.getRefreshForAccess(pending));
        assertEquals("", first.getRefresh());

        first.saveTokens("synthetic-access-b", "synthetic-refresh-b");
        assertFalse(second.saveRefreshedTokens(pending, "synthetic-late-access", "synthetic-late-refresh"));
        assertEquals("synthetic-access-b", first.getAccess());
        assertEquals("synthetic-refresh-b", first.getRefresh());
        assertEquals("", second.getRefreshForAccess(pending));

        // Re-entering the old access token is still a different login, not the pending session.
        first.saveTokens("synthetic-access-a", "");
        assertNotEquals(pending.generation, second.authSnapshot().generation);
        assertFalse(second.saveRefreshedTokens(pending, "synthetic-late-access", "synthetic-late-refresh"));
        assertEquals("synthetic-access-a", second.getAccess());
        assertEquals("", second.getRefresh());
    }

    @Test public void completedRefreshIsVisibleToOtherStoreInTheSameSession() {
        first.saveTokens("synthetic-access-a", "synthetic-refresh-a");
        Store.AuthSnapshot pending = second.authSnapshot();

        assertTrue(first.saveRefreshedTokens(pending, "synthetic-access-renewed", "synthetic-refresh-renewed"));
        Store.AuthSnapshot current = second.authSnapshot();
        assertEquals("Refreshing must preserve the session so parallel requests can reuse it",
                pending.generation, current.generation);
        assertEquals("synthetic-access-renewed", current.access);
        assertEquals("synthetic-refresh-renewed", second.getRefreshForAccess(current));

        // A second response for the old access token must not undo the completed refresh.
        assertFalse(second.saveRefreshedTokens(pending, "synthetic-obsolete-access", "synthetic-obsolete-refresh"));
        assertEquals("synthetic-access-renewed", first.getAccess());
        assertEquals("synthetic-refresh-renewed", first.getRefresh());
        assertEquals("", second.getRefreshForAccess(pending));
    }
}
