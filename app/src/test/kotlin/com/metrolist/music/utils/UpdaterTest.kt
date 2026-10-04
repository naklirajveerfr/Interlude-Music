package com.metrolist.music.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    @Test
    fun parsesVersionFile() {
        assertEquals("1.0", Updater.parseVersion("1.0\n"))
        assertEquals("1.2.3", Updater.parseVersion("  v1.2.3 "))
        assertNull(Updater.parseVersion("404: Not Found"))
        assertNull(Updater.parseVersion(""))
    }

    @Test
    fun updateOnlyWhenLatestIsHigher() {
        assertTrue(Updater.isUpdateAvailable("1.0", "1.1"))
        assertTrue(Updater.isUpdateAvailable("1.0", "1.0.1"))
        assertFalse(Updater.isUpdateAvailable("1.0", "1.0"))
        assertFalse(Updater.isUpdateAvailable("1.1", "1.0"))
    }
}
