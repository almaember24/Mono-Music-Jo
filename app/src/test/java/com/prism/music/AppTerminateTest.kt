package com.prism.music

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.isActive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Robolectric ends each test's app with onTerminate: Prism's work stops and its database closes then, so nothing keeps
 * reading a database Robolectric has let go of (the test JVM crashed in SQLite's native code, 2026-11-07).
 */
@RunWith(RobolectricTestRunner::class)
class AppTerminateTest {
    @Test
    fun terminateStopsTheAppsWorkAndClosesItsDatabase() {
        val app = ApplicationProvider.getApplicationContext<PrismApp>()
        val c = app.container
        c.library.liked.value // the library's lists are reading the database
        assertTrue(c.scope.isActive)
        c.db.openHelper.writableDatabase // Room opens it on first use
        assertTrue(c.db.isOpen)
        app.onTerminate()
        assertFalse(c.scope.isActive)
        assertFalse(c.db.isOpen)
    }
}
